from pathlib import Path
import subprocess
import tempfile
import unittest

from PIL import Image


class FullProcessExportValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir='/media/ramdisk')
        self.addCleanup(self.temporary.cleanup)
        self.matrix = Path(self.temporary.name) / 'matrix'
        pyramid = self.matrix / 'pyramidalImage'
        pyramid.mkdir(parents=True)
        Image.new('RGB', (256, 256), 'green').save(pyramid / '0.png')
        key = '0' * 11
        path = pyramid.joinpath(*key[1:], key + '.png')
        path.parent.mkdir(parents=True)
        Image.new('RGB', (256, 256), 'green').save(path)
        self.export_log = Path(self.temporary.name) / '32.log'
        self.export_log.write_text(
            'WARNING - 1 layer(s) with NO placed tiles will NOT appear\n'
            'Export complete: 2 tiles processed to pyramid\n'
        )

    def validate(self, level):
        source = Path(__file__).with_name('runFullProcess.sh').read_text()
        function = source[source.index('validate_export_log_and_pyramid() {'):
                          source.index('\nquadkey_file_path() {')]
        setup = '''set -euo pipefail
MATRIX_DIR="$1"
die() { printf '%s\\n' "$*" >&2; exit 1; }
log() { printf '%s\\n' "$*"; }
count_files() { find "$1" -type f -name "$2" | wc -l; }
'''
        return subprocess.run(
            ['bash', '-c', setup + function + '\nvalidate_export_log_and_pyramid "$2" "$3"',
             'validation-test', str(self.matrix), str(self.export_log), level],
            capture_output=True, text=True,
        )

    def test_level_only_accepts_valid_export_with_unplaced_other_layers(self):
        self.assertEqual(0, self.validate('10').returncode)

    def test_full_export_still_rejects_unplaced_layers(self):
        self.assertNotEqual(0, self.validate('').returncode)

    def test_export_errors_still_block_level_only_merge(self):
        for error in ['Export failed before writing', '(1 failed)']:
            with self.subTest(error=error):
                self.export_log.write_text(error + '\nExport complete: 2 tiles processed\n')
                self.assertNotEqual(0, self.validate('10').returncode)

    def test_bad_file_layout_still_blocks_level_only_merge(self):
        Image.new('RGB', (256, 256)).save(self.matrix / 'pyramidalImage/01.png')
        self.assertNotEqual(0, self.validate('10').returncode)


if __name__ == '__main__':
    unittest.main()
