import importlib.util
import tempfile
import unittest
from pathlib import Path
from PIL import Image

spec = importlib.util.spec_from_file_location('delta', Path(__file__).with_name('buildLevelOnlyDelta.py'))
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)


class LevelOnlyDeltaTest(unittest.TestCase):
    def test_missing_ancestor_is_reconstructed_only_from_four_complete_children(self):
        with tempfile.TemporaryDirectory(dir='/media/ramdisk') as work:
            root = Path(work)
            source, destination, target = (root / name for name in ['source', 'destination', 'target'])
            for folder, key, color in [(destination, '0', 'white'),
                                       (source, '000', 'red'), (source, '001', 'green'),
                                       (source, '002', 'blue'), (source, '003', 'yellow')]:
                path = delta.tile_path(folder, key)
                path.parent.mkdir(parents=True, exist_ok=True)
                Image.new('RGB', (256, 256), color).save(path)
            delta.build(source, target, destination, 2)
            with Image.open(delta.tile_path(target, '00')) as image:
                self.assertEqual((255, 255, 0, 255), image.getpixel((64, 64)))
                self.assertEqual((255, 0, 0, 255), image.getpixel((64, 192)))
            delta.tile_path(source, '003').unlink()
            with self.assertRaisesRegex(ValueError, 'lack ancestor 00'):
                delta.build(source, root / 'incomplete', destination, 2)

    def test_new_region_uses_native_missing_ancestor_and_preserves_existing_root(self):
        with tempfile.TemporaryDirectory(dir='/media/ramdisk') as work:
            root = Path(work)
            source, destination, target = (root / name for name in ['source', 'destination', 'target'])
            for folder, key, color in [(destination, '0', 'white'), (source, '0', 'red'),
                                       (source, '00', 'blue'), (source, '000', 'green')]:
                path = delta.tile_path(folder, key)
                path.parent.mkdir(parents=True, exist_ok=True)
                Image.new('RGB', (256, 256), color).save(path)
            delta.build(source, target, destination, 2)
            self.assertEqual(delta.tile_path(destination, '0').read_bytes(),
                             delta.tile_path(target, '0').read_bytes())
            self.assertEqual(delta.tile_path(source, '00').read_bytes(),
                             delta.tile_path(target, '00').read_bytes())
            self.assertFalse(delta.tile_path(destination, '00').exists())
            delta.tile_path(source, '00').unlink()
            with self.assertRaisesRegex(ValueError, 'lack ancestor 00'):
                delta.build(source, root / 'invalid-target', destination, 2)

    def test_l9_to_l14_chain_selects_native_and_complete_parents_only(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            source, destination, target = (root / name for name in ['source', 'destination', 'target'])
            source.mkdir()
            destination.mkdir()
            base = '0' + '3' * 9
            def put(folder, key, color):
                path = delta.tile_path(folder, key)
                path.parent.mkdir(parents=True, exist_ok=True)
                Image.new('RGB', (256, 256), color).save(path)
            for length in range(1, 11):
                put(destination, (base + '0')[:length], 'white')
            # Native L10 remains available even with an L14 descendant.
            put(source, base + '0', 'green')
            put(source, base + '0' + '3333', 'blue')
            # All four L11 children can reconstruct a missing L10.
            for digit, color in zip('0123', ['red', 'green', 'blue', 'yellow']):
                put(source, base + '1' + digit, color)
            # One missing quadrant must never turn into nominal coverage.
            for digit in '012':
                put(source, base + '2' + digit, 'red')
            delta.build(source, target, destination, 10)
            self.assertTrue(delta.tile_path(target, base + '0').is_file())
            self.assertTrue(delta.tile_path(target, base + '1').is_file())
            self.assertFalse(delta.tile_path(target, base + '2').exists())
            self.assertFalse(delta.tile_path(target, base + '03333').exists())
            with Image.open(delta.tile_path(target, base + '1')) as image:
                self.assertEqual((255, 255, 0, 255), image.getpixel((64, 64)))
                self.assertEqual((255, 0, 0, 255), image.getpixel((64, 192)))
            self.assertEqual(2, sum(delta.tile_path(target, base + digit).is_file() for digit in '0123'))

    def test_physical_rectangle_becomes_complete_with_l10_parents(self):
        import sys
        sys.path.insert(0, str(Path(__file__).parent))
        from superPipelineController import destination_contains_job_area, quadkey_from_coordinates
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            source, destination, target = (root / name for name in ['source', 'destination', 'target'])
            source.mkdir()
            destination.mkdir()
            keys = [quadkey_from_coordinates(10, col, row) for col in [190, 191] for row in [670, 671]]
            def put(folder, key):
                path = delta.tile_path(folder, key)
                path.parent.mkdir(parents=True, exist_ok=True)
                Image.new('RGB', (256, 256), 'green').save(path)
            for key in keys:
                for length in range(1, 11):
                    put(destination, key[:length])
            put(destination, keys[0])
            put(source, keys[1])
            put(source, keys[2])
            for digit in '0123':
                put(source, keys[3] + digit)
            step = 360 / 1024
            job = dict(completionLevel=10, latitude=670 * step - 180, longitude=190 * step - 180,
                       latitudeSpan=2 * step, longitudeSpan=2 * step)
            self.assertFalse(destination_contains_job_area(destination, job))
            delta.build(source, target, destination, 10)
            self.assertTrue(destination_contains_job_area(destination, job, target))
            for key in keys[1:]:
                put(destination, key)
            self.assertTrue(destination_contains_job_area(destination, job))


if __name__ == '__main__':
    unittest.main()
