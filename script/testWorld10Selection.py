import shlex
import unittest

from updateWorld10EmptyGroups import select_groups


class NorthernSelectionTest(unittest.TestCase):
    def test_northern_recapture_covers_every_longitude_including_existing_pngs(self):
        present = {row * 1024 + column for row in range(680, 705) for column in range(1024)}
        jobs = select_groups(present, 95, northern_band_only=True)
        self.assertEqual(list(range(-180, 180, 8)), [j['longitude'] for j in jobs])
        for job in jobs:
            self.assertEqual((59, 9, 8), (job['latitude'], job['latitudeSpan'], job['longitudeSpan']))
            self.assertEqual('63.5,' + str(job['longitude'] + 4), job['center'])
            self.assertTrue(job['force'])
            self.assertEqual(0, job['missingTiles'])
            self.assertEqual(['180000', '3200', '90000', '9', '8'], shlex.split(job['routeCommand'])[4:])
        self.assertTrue({-140, -132, -124}.issubset({j['longitude'] for j in jobs}))

    def test_normal_selection_adds_northern_band_and_retains_threshold(self):
        jobs = select_groups(set(), 95)
        self.assertEqual(720, len(jobs))
        self.assertEqual(45, sum(j['latitude'] == 59 for j in jobs))
        self.assertTrue(all(not j['force'] for j in jobs))

    def test_normal_northern_selection_observes_95_percent_boundary(self):
        present = {680 * 1024 + i for i in range(23)} | {681 * 1024 + i for i in range(5)}
        def selected():
            return (59, -180) in {(j['latitude'], j['longitude']) for j in select_groups(present, 95)}
        self.assertTrue(selected())
        present.add(681 * 1024 + 5)
        self.assertFalse(selected())


if __name__ == '__main__':
    unittest.main()
