"""Run with python3 -m unittest discover -s scripts -p 'test_*.py'."""
import unittest
from pathlib import Path

from title_normalization import normalize, relaxed_key


class TitleNormalizationTest(unittest.TestCase):
    def test_shared_runtime_and_builder_fixtures(self):
        path = Path(__file__).resolve().parents[1] / "app/src/test/resources/title-normalization.tsv"
        for line in path.read_text().splitlines():
            title, exact, relaxed = line.split("\t")
            with self.subTest(title=title):
                self.assertEqual(exact, normalize(title))
                self.assertEqual(relaxed, relaxed_key(title))
                self.assertEqual(exact, normalize(exact))
