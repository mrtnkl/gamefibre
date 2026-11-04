"""Exercise the complete refresh offline, using small upstream-format snapshots."""
import contextlib
import csv
import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("updater", Path(__file__).with_name("update-bundled-data.py"))
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class BundledDataUpdateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.root = self.base / "repo"
        self.root.mkdir()
        self.cache = self.base / "cache"
        self.cache.mkdir()
        self.inputs = self.base / "inputs"
        self.inputs.mkdir()
        games = {
            "10": dict(name="Example", tags={"RPG": 30, "Open World": 10}, metacritic_score=90,
                       positive=80, negative=20, release_date="Jan 01, 2024"),
            "20": dict(name="Shared", tags={"Puzzle": 20}, positive=0, negative=0),
            "30": dict(name="Shared", tags=[], positive=0, negative=0),
        }
        self.json = self.inputs / "games.json"
        self.json.write_text(json.dumps(games))
        self.csv = self.inputs / "games.csv"
        self.csv.write_text('AppID,Name\n10,Example\n20,Shared\n30,Shared\n')
        self.hltb = self.inputs / "hltb.csv"
        self.hltb.write_text('steam_id,game_name,comp_main,comp_plus,comp_100\n'
                             '10,Example alias,3600,7200,10800\n10,Example,9999,0,0\n'
                             '20,Shared,0,0,0\n30,Shared,0,0,0\n0,No Steam ID,100,0,0\n')
        def source(path):
            return dict(url=f"https://example.test/{path.name}", sha256=updater.checksum(path), size=path.stat().st_size)
        self.sources = dict(steam=dict(revision="a" * 40, files={"games.csv": source(self.csv), "games.json": source(self.json)}),
                            hltb=dict(release="scrape-example", published_at="2026-01-01T00:00:00Z", **source(self.hltb)))
        self.paths = {updater.checksum(path): path for path in (self.csv, self.json, self.hltb)}

    def refresh(self):
        with patch.object(updater, "resolve_sources", return_value=self.sources), \
             patch.object(updater, "download", side_effect=lambda source, cache: self.paths[source["sha256"]]), \
             contextlib.redirect_stdout(io.StringIO()):
            updater.update(self.root, self.cache)

    def test_complete_refresh_keeps_all_indexes_and_compiled_tags_consistent(self):
        self.refresh()
        assets = self.root / "app/src/main/assets"
        with (assets / "hltb-min.csv").open() as stream:
            times = list(csv.DictReader(stream))
        self.assertEqual([dict(steam_id="10", comp_main="3600", comp_plus="7200", comp_100="10800")], times)
        self.assertIn("10\tExample alias\n", (assets / "hltb-names.tsv").read_text())
        self.assertNotIn("No Steam ID", (assets / "hltb-names.tsv").read_text())
        self.assertEqual((b"HLTB", 1, 1), struct.unpack(">4sii", (assets / "hltb-index-v1.bin").read_bytes()[:12]))
        for file, magic in (("steam-titles-v2.bin.xz", b"STIX"), ("steam-tags-v1.bin.xz", b"STTG"),
                            ("steam-catalog-v1.bin.xz", b"STCA")):
            self.assertEqual(magic, lzma.decompress((assets / file).read_bytes())[:4])
        manifest = json.loads((assets / "bundled-data-sources.json").read_text())
        for file, digest in manifest["outputs"].items():
            self.assertEqual(digest, updater.checksum(self.root / file))
        titles = json.loads((assets / "steam-titles-v2.json").read_text())
        self.assertEqual(2, titles["unique_titles"])  # Shared remains ambiguous; the HLTB alias is merged.
        self.assertEqual(updater.checksum(assets / "hltb-names.tsv"), titles["aliases_sha256"])
        for name in ("steam-titles-v2.json", "steam-tags-v1.json", "steam-catalog-v1.json"):
            data = json.loads((assets / name).read_text())
            self.assertEqual("a" * 40, data["source_revision"])
            self.assertIn("/" + "a" * 40 + "/", data["source"])
        labels = (self.root / "app/src/main/res/values/steam_catalog_tags.xml").read_text()
        self.assertIn('<item>RPG</item>', labels)
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.refresh()
        self.assertEqual(before, {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_failed_build_preserves_every_previous_output(self):
        self.refresh()
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.sources["steam"]["files"]["games.json"]["sha256"] = "0" * 64
        self.paths["0" * 64] = self.json
        with self.assertRaises(ValueError):
            self.refresh()
        self.assertEqual(before, {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_bad_hltb_schema_or_values_are_rejected(self):
        for contents in ('steam_id,game_name\n10,Missing times\n',
                         'steam_id,game_name,comp_main,comp_plus,comp_100\n10,Negative,-1,0,0\n',
                         'steam_id,game_name,comp_main,comp_plus,comp_100\n0,No IDs,1,0,0\n'):
            self.hltb.write_text(contents)
            with self.assertRaises(ValueError):
                updater.prepare_hltb(self.hltb, self.cache)

    def test_download_verifies_cache_and_rejects_corrupt_payload(self):
        payload = b"verified data"
        source = dict(url="https://example.test/data", sha256=hashlib.sha256(payload).hexdigest(), size=len(payload))
        with patch.object(updater, "request", return_value=io.BytesIO(payload)):
            path = updater.download(source, self.cache)
        with patch.object(updater, "request", side_effect=AssertionError("Cache should avoid download")):
            self.assertEqual(path, updater.download(source, self.cache))
        path.write_bytes(b"corrupt cache")
        with patch.object(updater, "request", side_effect=lambda url: io.BytesIO(b"corrupt network")), \
             patch.object(updater.time, "sleep"):
            with self.assertRaises(OSError):
                updater.download(source, self.cache)
        self.assertFalse(path.with_suffix('.part').exists())

    def test_source_resolution_pins_downloads_to_one_revision_and_checksums(self):
        revision = "b" * 40
        tree = [dict(path=name, size=5, lfs=dict(oid="c" * 64)) for name in ("games.csv", "games.json")]
        release = dict(draft=False, prerelease=False, tag_name="scrape-test", published_at="2026-01-01",
                       assets=[dict(name="howlongtobeat_games.csv", size=5, digest="sha256:" + "d" * 64,
                                    browser_download_url=f"https://github.com/{updater.HLTB_REPO}/releases/download/scrape-test/howlongtobeat_games.csv")])
        with patch.object(updater, "get_json", side_effect=[dict(sha=revision), tree, release]) as fetch:
            sources = updater.resolve_sources()
        self.assertEqual(f"{updater.STEAM_API}/tree/{revision}", fetch.call_args_list[1].args[0])
        self.assertTrue(all(f"/{revision}/" in s["url"] for s in sources["steam"]["files"].values()))
        self.assertEqual("d" * 64, sources["hltb"]["sha256"])


if __name__ == "__main__":
    unittest.main()
