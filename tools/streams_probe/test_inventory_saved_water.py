import struct
import unittest
import inventory_saved_water as inventory


def text(value):
    raw = value.encode('utf-8')
    return struct.pack('>H', len(raw)) + raw


def tag(kind, name, payload):
    return bytes([kind]) + text(name) + payload


def packed(values, size):
    bits = max(4, (size - 1).bit_length())
    per_word = 64 // bits
    words = [0] * ((4096 + per_word - 1) // per_word)
    for i, value in enumerate(values):
        words[i // per_word] |= value << ((i % per_word) * bits)
    return struct.pack('>i', len(words)) + struct.pack('>' + 'Q' * len(words), *words)


class InventoryTest(unittest.TestCase):
    def test_word_padding_and_palette_boundaries(self):
        values = [i % 17 for i in range(4096)]
        data = packed(values, 17)
        self.assertEqual(values, inventory.indices(data, (12, 0, len(data), None), 17))

    def test_uniform_and_malformed_storage(self):
        self.assertEqual([0] * 4096, inventory.indices(b'', None, 1))
        with self.assertRaises(ValueError):
            inventory.indices(b'', None, 2)
        data = packed([2] * 4096, 3)
        with self.assertRaises(ValueError):
            inventory.indices(data, (12, 0, len(data), None), 2)
        with self.assertRaises(ValueError):
            inventory.indices(data, (12, 0, len(data) - 8, None), 3)

    def test_sources_excluded_and_negative_chunk_coordinates(self):
        palette = b''
        for name, level in [('minecraft:air', None), ('minecraft:water', '0'), ('minecraft:water', '8')]:
            state = tag(8, 'Name', text(name))
            if level is not None:
                state += tag(10, 'Properties', tag(8, 'level', text(level)) + b'\0')
            palette += state + b'\0'
        values = [0] * 4096
        values[0] = 1  # source at y64 is retained
        values[1 + 16 * 2 + 256 * 3] = 2
        block_states = tag(9, 'palette', b'\x0a' + struct.pack('>i', 3) + palette)
        block_states += tag(12, 'data', packed(values, 3)) + b'\0'
        section = tag(1, 'Y', b'\x04') + tag(10, 'block_states', block_states) + b'\0'
        raw = b'\x0a\0\0' + tag(3, 'xPos', struct.pack('>i', -2)) + tag(3, 'zPos', struct.pack('>i', -3))
        raw += tag(8, 'Status', text('minecraft:full')) + tag(9, 'sections', b'\x0a' + struct.pack('>i', 1) + section) + b'\0'
        status, found, count = inventory.chunk_flows(raw, -2, -3)
        self.assertEqual(('minecraft:full', [(-31, 67, -46)], 1), (status, found, count))
        with self.assertRaises(ValueError):
            inventory.chunk_flows(raw, -1, -3)


if __name__ == '__main__':
    unittest.main()
