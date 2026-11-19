import unittest

from shore_check import coral_volume


class ReefVolumeTest(unittest.TestCase):
    def test_living_blocks_count_even_when_not_the_visible_column_cap(self):
        class Volume:
            def state(self,x,y,z):
                return {55:("minecraft:brain_coral_block",False),
                        56:("minecraft:brain_coral_wall_fan",True),
                        57:("minecraft:dead_brain_coral_fan",True),
                        58:("example:brain_coral",True)}.get(y,("minecraft:water",True))
        self.assertEqual(2,coral_volume(Volume(),[0,0,0,0]))

    def test_absence_requires_the_full_y_range(self):
        class Partial:
            def state(self,x,y,z):
                return None if y==44 else ("minecraft:water",True)
        with self.assertRaisesRegex(ValueError,"unreadable"):
            coral_volume(Partial(),[0,0,0,0])
