"""Limit the inspected Photon pack's vegetation animation to nine player chunks."""
import argparse
import copy
import difflib
import hashlib
import json
from pathlib import Path
import zipfile

SOURCE_SHA256 = 'e96c6513d1e98caffbd851bc7aad8f81a72cbfe6b4918365bb3e59c4b87261fc'
MEMBER = 'shaders/include/vertex/displacement.glsl'


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError('Source anchor changed: ' + before[:80])
    return text.replace(before, after, 1)


def patch(text):
    text = replace_once(text, '#ifdef WATER_DISPLACEMENT\nfloat gerstner_wave(', '''// Use the owning block centre, not individual vertices, at chunk boundaries.
// eyePosition follows the player even when the third-person camera moves.
bool vegetation_chunk_active(vec3 block_center) {
    vec2 delta = abs(floor(block_center.xz / 16.0) - floor(eyePosition.xz / 16.0));
    return max(delta.x, delta.y) <= 1.0;
}

#ifdef WATER_DISPLACEMENT
float gerstner_wave(''')
    text = replace_once(text, '#ifdef WAVING_PLANTS\n    bool farmland', '''#ifdef WAVING_PLANTS
    // Keep the static crop yaw above, but skip all animation outside 3x3 chunks.
    if (!vegetation_chunk_active(center)) return world_pos;
    bool farmland''')
    text = replace_once(text, '    float wind_speed = 0.3;\n', '''#if defined WAVING_PLANTS || defined WAVING_LEAVES
    bool vegetation = material_mask == MATERIAL_SMALL_PLANTS
        || material_mask == MATERIAL_OPEN_EYEBLOSSOM
        || material_mask == MATERIAL_TALL_PLANTS_LOWER
        || material_mask == MATERIAL_TALL_PLANTS_UPPER
        || material_mask == MATERIAL_LEAVES;
    vec3 block_center = floor(world_pos + at_midBlock * (1.0 / 64.0)) + 0.5;
    if (vegetation && !vegetation_chunk_active(block_center)) return world_pos;
#endif

    float wind_speed = 0.3;
''')
    return text


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    source_hash = hashlib.sha256(args.source.read_bytes()).hexdigest()
    if source_hash != SOURCE_SHA256:
        raise SystemExit('Source changed; inspect it before updating the pinned hash')
    if args.output.exists():
        raise SystemExit('Output must be a new file')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.source) as src:
        original = src.read(MEMBER).decode('utf-8')
        modified = patch(original)
        with zipfile.ZipFile(args.output, 'x') as dst:
            dst.comment = src.comment
            for info in src.infolist():
                dst.writestr(copy.copy(info), modified.encode('utf-8') if info.filename == MEMBER else src.read(info))
        with zipfile.ZipFile(args.output) as dst:
            assert src.namelist() == dst.namelist()
            changed = [n for n in src.namelist() if src.read(n) != dst.read(n)]
            assert changed == [MEMBER], changed
            assert dst.testzip() is None
    args.output.with_suffix('.diff').write_text(''.join(difflib.unified_diff(
        original.splitlines(True), modified.splitlines(True),
        fromfile='original/' + MEMBER, tofile='modified/' + MEMBER)), encoding='utf-8')
    manifest = {'source_sha256': source_hash,
                'output_sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
                'changed_members': changed, 'chunk_radius': 1,
                'center': 'player eyePosition XZ', 'boundary': 'hard cutoff by owning block',
                'runtime_validated': False}
    args.output.with_suffix('.manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps(manifest))


if __name__ == '__main__':
    main()
