"""Tests that Tools.xcstrings_format matches Xcode's own canonical
serialization of `.xcstrings` catalogs byte-for-byte, so writing a catalog
with this tool doesn't cause every subsequent Xcode build to show a
100%-line no-op diff.

Run: python3 -m unittest Tools.test_xcstrings_format -v   (from repo root)
"""
import unittest

import xcstrings_format as xf


class DumpsCatalogTests(unittest.TestCase):
    def test_keys_sorted_at_every_level(self) -> None:
        catalog = {
            "version": "1.0",
            "strings": {
                "b": {"localizations": {"fr": {}, "de": {}}},
                "a": {},
            },
            "sourceLanguage": "en",
        }
        out = xf.dumps_catalog(catalog)
        self.assertLess(out.index('"a"'), out.index('"b"'))
        self.assertLess(out.index('"de"'), out.index('"fr"'))
        self.assertLess(out.index('"sourceLanguage"'), out.index('"strings"'))
        self.assertLess(out.index('"strings"'), out.index('"version"'))

    def test_space_on_both_sides_of_colon(self) -> None:
        out = xf.dumps_catalog({"sourceLanguage": "en", "strings": {}, "version": "1.0"})
        self.assertIn('"sourceLanguage" : "en"', out)
        self.assertNotIn('"sourceLanguage": "en"', out)

    def test_no_trailing_newline(self) -> None:
        out = xf.dumps_catalog({"sourceLanguage": "en", "strings": {}, "version": "1.0"})
        self.assertFalse(out.endswith("\n"))
        self.assertTrue(out.endswith("}"))

    def test_empty_object_gets_blank_line_not_braces(self) -> None:
        # Xcode never collapses an empty object to "{}" — it always emits a
        # blank line between the braces, matching a bare stub key like
        # `" %@" : {}` for an already-extracted string with no metadata yet.
        out = xf.dumps_catalog({"sourceLanguage": "en", "strings": {"x": {}}, "version": "1.0"})
        self.assertIn('"x" : {\n\n    }', out)
        self.assertNotIn("{}", out)

    def test_write_catalog_round_trips(self) -> None:
        import json
        import tempfile
        from pathlib import Path

        catalog = {"sourceLanguage": "en", "strings": {"Hi": {}}, "version": "1.0"}
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "Localizable.xcstrings"
            xf.write_catalog(path, catalog)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), catalog)
            raw = path.read_bytes()
            self.assertFalse(raw.endswith(b"\n"))


class CanonicalizeTests(unittest.TestCase):
    def test_reformats_a_differently_formatted_file_in_place(self) -> None:
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "Localizable.xcstrings"
            # Xcode-rewritten-looking content but out of canonical (sorted,
            # tightly-spaced) order — simulates what a build just produced.
            path.write_text(
                '{\n  "version" : "1.0",\n  "strings" : {"b": {}, "a": {}},\n'
                '  "sourceLanguage" : "en"\n}\n',
                encoding="utf-8",
            )
            changed = xf.canonicalize(path)
            self.assertTrue(changed)
            out = path.read_text(encoding="utf-8")
            self.assertLess(out.index('"a"'), out.index('"b"'))
            self.assertFalse(out.endswith("\n"))

    def test_already_canonical_file_is_reported_unchanged(self) -> None:
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "Localizable.xcstrings"
            xf.write_catalog(path, {"sourceLanguage": "en", "strings": {}, "version": "1.0"})
            before = path.read_bytes()
            changed = xf.canonicalize(path)
            self.assertFalse(changed)
            self.assertEqual(path.read_bytes(), before)

    def test_cli_exits_nonzero_with_no_args(self) -> None:
        self.assertEqual(xf.main([]), 2)


if __name__ == "__main__":
    unittest.main()
