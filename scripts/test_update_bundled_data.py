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
        assets = self.root / "app/src/main/assets"
        assets.mkdir(parents=True)
        self.hltb = assets / "hltb-min.csv"
        self.hltb.write_text('steam_id,comp_main,comp_plus,comp_100\n10,3600,7200,10800\n')
        self.names = assets / "hltb-names.tsv"
        self.names.write_text('10\tExample alias\n10\tExample\n20\tShared\n30\tShared\n')
        def source(path):
            return dict(url=f"https://example.test/{path.name}", sha256=updater.checksum(path), size=path.stat().st_size)
        self.sources = dict(steam=dict(revision="a" * 40, files={"games.csv": source(self.csv), "games.json": source(self.json)}))
        self.hltb_metadata = dict(mode="fixed", release="fixed-test",
                                  files={path.name: updater.checksum(path) for path in (self.hltb, self.names)},
                                  coverage=dict(time_records=1, title_aliases=4))
        self.paths = {updater.checksum(path): path for path in (self.csv, self.json)}

    def refresh(self):
        with patch.object(updater, "resolve_sources", return_value=self.sources), \
             patch.object(updater, "FIXED_HLTB", self.hltb_metadata), \
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
        self.assertEqual(self.hltb_metadata, manifest["hltb"])
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

    def test_missing_fixed_input_fails_before_network_or_output_changes(self):
        self.refresh()
        self.hltb.unlink()
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        with patch.object(updater, "resolve_sources") as resolve, \
             patch.object(updater, "FIXED_HLTB", self.hltb_metadata):
            with self.assertRaisesRegex(ValueError, 'Fixed HLTB dataset is missing or modified'):
                updater.update(self.root, self.cache)
        resolve.assert_not_called()
        self.assertEqual(before, {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_steam_refresh_keeps_fixed_hltb_inputs_and_index_unchanged(self):
        self.refresh()
        assets = self.root / "app/src/main/assets"
        fixed = {name: (assets / name).read_bytes() for name in ('hltb-min.csv', 'hltb-names.tsv', 'hltb-index-v1.bin')}
        self.csv.write_text('AppID,Name\n10,New Steam name\n20,Shared\n30,Shared\n')
        digest = updater.checksum(self.csv)
        self.sources['steam']['files']['games.csv'].update(sha256=digest, size=self.csv.stat().st_size)
        self.paths[digest] = self.csv
        self.refresh()
        self.assertEqual(fixed, {name: (assets / name).read_bytes() for name in fixed})

    def test_modified_fixed_input_is_rejected(self):
        self.names.write_text('10\tModified\n')
        with patch.object(updater, 'FIXED_HLTB', self.hltb_metadata), \
             patch.object(updater, 'request', side_effect=AssertionError('No HLTB network requests')):
            with self.assertRaisesRegex(ValueError, 'hltb-names.tsv'):
                updater.prepare_fixed_hltb(self.root, self.cache)

    def test_original_fixed_inputs_rebuild_the_original_binary_offline(self):
        with patch.object(updater, 'request', side_effect=AssertionError('No HLTB network requests')), \
             contextlib.redirect_stdout(io.StringIO()):
            metadata = updater.prepare_fixed_hltb(updater.ROOT, self.cache)
            updater.builder('build-hltb-index').build(self.cache)
        self.assertEqual('scrape-2026-06-28', metadata['release'])
        self.assertEqual('6029d6c9ab7b919d2524491d7d456e00bcb7742c20e8c754d6e2dcf84b0ff5b8',
                         updater.checksum(self.cache / 'hltb-index-v1.bin'))

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
        with patch.object(updater, "get_json", side_effect=[dict(sha=revision), tree]) as fetch:
            sources = updater.resolve_sources()
        self.assertEqual(f"{updater.STEAM_API}/tree/{revision}", fetch.call_args_list[1].args[0])
        self.assertTrue(all(f"/{revision}/" in s["url"] for s in sources["steam"]["files"].values()))
        self.assertEqual(2, fetch.call_count)  # Only Steam sources are resolved online.
        self.assertNotIn("hltb", sources)


if __name__ == "__main__":
    unittest.main()
