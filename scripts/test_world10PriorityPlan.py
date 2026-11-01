import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import superPipelineController as controller


class World10PriorityPlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = Path(__file__).with_name('runWorld10PriorityContinental.sh')
        cls.jobs = [json.loads(line) for line in subprocess.check_output(
            [str(source.resolve()), '--emit-jobs'], text=True).splitlines()]

    def test_only_northern_regions_and_caspian_remain_with_reviewed_zigzag(self):
        self.assertEqual([(60, -124), (60, -132), (60, -140),
                          (36, 44), (36, 52)],
                         [(j['latitude'], j['longitude']) for j in self.jobs])
        for job in self.jobs:
            route = shlex.split(job['routeCommand'])
            height = '90000' if job['latitude'] == 60 else '100000'
            self.assertEqual(['180000', '3200', height, '8', '8'], route[4:])
            self.assertEqual(int(route[4]), job['captureStep'])
            self.assertEqual(int(route[6]), job['captureHeight'])
            self.assertTrue(job['mustCommit'])
            self.assertEqual(10, job['mergeLevelOnly'])
            self.assertEqual([str(job['latitude']), str(job['longitude'])], route[2:4])
        for job in self.jobs[:3]:
            self.assertEqual(68, job['latitude'] + job['latitudeSpan'])
            self.assertEqual(8, job['longitudeSpan'])

    def test_running_one_region_keeps_the_entire_reviewed_viewer_plan(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            with patch.object(controller, 'PROJECT_ROOT', root), patch.object(
                controller, 'load_jobs', return_value=self.jobs
            ) as loader:
                controller.write_coverage_grid_plan('world10-priority-continental', self.jobs[:1])
                loader.assert_called_once_with('world10-priority-continental', None, None)
            actual = [json.loads(line) for line in
                      (root / 'scripts/world10PlannedGroups.jsonl').read_text().splitlines()]
            self.assertEqual(self.jobs, actual)

    def test_successful_coastal_merge_commits_despite_incomplete_rectangle(self):
        with tempfile.TemporaryDirectory(dir='/media/ramdisk') as temporary:
            root = Path(temporary)
            slot = root / 'output'
            job = dict(self.jobs[0])
            controller.write_slot_job(slot, job, 'CAPTURE_READY')
            (slot / '_pipeline/capture.trace').write_bytes(b'test-trace')
            with patch.object(controller, 'run_command'), patch.object(
                controller, 'destination_contains_job_area', return_value=False
            ), patch.object(controller, 'capture_coverage_snapshot'):
                controller.run_stage_b(job, slot, root / 'matrix', root / 'destination',
                                       root / 'logs', False)
            self.assertEqual('COMMITTED', controller.read_slot_job(slot)['state'])


if __name__ == '__main__':
    unittest.main()
