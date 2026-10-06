"""Read-only inventory of ordinary flowing water above sea level in saved FULL chunks."""
import argparse
import gzip
import hashlib
import importlib.util
import json
import re
import struct
import zlib
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('playtest_nbt', ROOT / 'tools/river_planner/prepare-playtest.py')
nbt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nbt)


def indices(raw, field, size):
    if size < 1:
        raise ValueError('Empty block palette')
    if size == 1 and field is None:
        return [0] * 4096
    if field is None or field[0] != 12:
        raise ValueError('Missing block-state long array')
    _, start, end, _ = field
    bits = max(4, (size - 1).bit_length())
    per_word = 64 // bits
    count = struct.unpack_from('>i', raw, start)[0]
    if count != (4096 + per_word - 1) // per_word or end - start != 4 + count * 8:
        raise ValueError('Incorrect packed block-state length')
    words = struct.unpack_from('>' + 'Q' * count, raw, start + 4)
    mask = (1 << bits) - 1
    result = [(words[i // per_word] >> ((i % per_word) * bits)) & mask for i in range(4096)]
    if any(value >= size for value in result):
        raise ValueError('Block-state palette index out of bounds')
    return result


def chunk_flows(raw, cx, cz, sea_level=63):
    fields = nbt.nbt_fields(raw)
    if fields[('xPos',)][3] != cx or fields[('zPos',)][3] != cz:
        raise ValueError('Chunk coordinates disagree with region index')
    status = fields[('Status',)][3]
    if status != 'minecraft:full':
        return status, [], 0
    sections = {}
    for key, value in fields.items():
        if len(key) < 3 or key[0] != 'sections':
            continue
        section = sections.setdefault(key[1], {'names': {}, 'levels': {}})
        if key[2:] == ('Y',):
            section['y'] = int(value[3])
        elif len(key) == 6 and key[2:4] == ('block_states', 'palette') and key[5] == 'Name':
            section['names'][int(key[4])] = value[3]
        elif len(key) == 7 and key[2:4] == ('block_states', 'palette') and key[5:] == ('Properties', 'level'):
            section['levels'][int(key[4])] = value[3]
        elif key[2:] == ('block_states', 'data'):
            section['data'] = value
    positions, decoded = [], 0
    for section in sections.values():
        sy = section['y']
        if sy * 16 + 15 <= sea_level:
            continue
        names = section['names']
        candidates = {i for i, name in names.items() if name == 'minecraft:water'
                      and section['levels'].get(i, '0') != '0'}
        if not candidates:
            continue
        if set(names) != set(range(len(names))):
            raise ValueError('Noncontiguous block palette')
        decoded += 1
        for i, palette_index in enumerate(indices(raw, section.get('data'), len(names))):
            y = sy * 16 + (i >> 8)
            if palette_index in candidates and y > sea_level:
                positions.append((cx * 16 + (i & 15), y, cz * 16 + ((i >> 4) & 15)))
    return status, positions, decoded


def inventory(world, output, sea_level=63):
    if not (world / 'level.dat').is_file() or not (world / 'region').is_dir():
        raise ValueError('Not a saved world')
    if (output / 'candidate-manifest.json').exists():
        raise FileExistsError('Refusing to overwrite completed inventory')
    output.mkdir(parents=True, exist_ok=True)
    full, positions, region_hashes, statuses = set(), [], {}, Counter()
    decoded = 0
    for region in sorted((world / 'region').glob('r.*.*.mca')):
        match = re.fullmatch(r'r\.(-?\d+)\.(-?\d+)\.mca', region.name)
        if not match:
            continue
        data = region.read_bytes()
        region_hashes[region.name] = hashlib.sha256(data).hexdigest()
        if not data:
            continue
        if len(data) < 8192:
            raise ValueError(f'Truncated region header: {region}')
        rx, rz = map(int, match.groups())
        for i in range(1024):
            offset = int.from_bytes(data[i * 4:i * 4 + 3], 'big') * 4096
            sectors = data[i * 4 + 3]
            if not offset and not sectors:
                continue
            if offset < 8192 or sectors == 0 or offset + sectors * 4096 > len(data):
                raise ValueError(f'Invalid chunk allocation: {region}:{i}')
            size = struct.unpack_from('>I', data, offset)[0]
            if size < 1 or size + 4 > sectors * 4096:
                raise ValueError('Invalid compressed chunk length')
            compression = data[offset + 4]
            if compression not in (1, 2, 3):
                raise ValueError(f'Unsupported/external chunk compression: {compression}')
            payload = data[offset + 5:offset + 4 + size]
            raw = {1: gzip.decompress, 2: zlib.decompress, 3: lambda p: p}[compression](payload)
            cx, cz = rx * 32 + i % 32, rz * 32 + i // 32
            status, found, count = chunk_flows(raw, cx, cz, sea_level)
            statuses[status] += 1
            if status == 'minecraft:full':
                full.add((cx, cz))
            positions.extend(found)
            decoded += count
        print(f'{region.name}: FULL={len(full)}, flowing={len(positions)}', flush=True)
    positions.sort()
    by_chunk = Counter((x >> 4, z >> 4) for x, y, z in positions)
    halo = {(x + dx, z + dz) for x, z in by_chunk for dx in (-1, 0, 1) for dz in (-1, 0, 1)}
    for name, rows in [('flow-candidate-positions.txt', positions),
                       ('flow-candidate-chunks.txt', sorted(by_chunk)),
                       ('flow-candidate-halo1-chunks.txt', sorted(halo)),
                       ('full-chunks.txt', sorted(full))]:
        (output / name).write_text(''.join(','.join(map(str, row)) + '\n' for row in rows), encoding='utf-8')
    result = {'sourceWorld': str(world), 'sourceLevelDatSha256': hashlib.sha256((world / 'level.dat').read_bytes()).hexdigest(),
              'sourceRegionHashes': region_hashes, 'statusCounts': dict(statuses), 'fullChunkCount': len(full),
              'seaLevel': sea_level, 'sectionsDecoded': decoded, 'candidatePositions': len(positions),
              'affectedChunks': len(by_chunk), 'countsPerChunk': {f'{x},{z}': n for (x, z), n in sorted(by_chunk.items())},
              'minY': min((p[1] for p in positions), default=None), 'maxY': max((p[1] for p in positions), default=None),
              'halo1Chunks': len(halo), 'missingFullHaloChunks': len(halo - full), 'missingFullHaloChunkList': sorted(halo - full)}
    (output / 'candidate-manifest.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({k: result[k] for k in ('fullChunkCount', 'candidatePositions', 'affectedChunks', 'halo1Chunks', 'missingFullHaloChunks')}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--world', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--sea-level', type=int, default=63)
    args = parser.parse_args()
    inventory(args.world.resolve(), args.output_dir.resolve(), args.sea_level)
