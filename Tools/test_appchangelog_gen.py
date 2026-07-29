#!/usr/bin/env python3
"""#878 — the generated Android What's New title must be a resource reference, not a literal.

A raw Kotlin `title = "..."` is a hardcoded literal that the i18n gate rejects, and because that gate
audits the WHOLE tree, one bad line red-checks every open PR on code none of them touched. That is not
hypothetical: it happened on 9.2.0 and again on 9.2.1, and both times it was cleared by hand afterwards
rather than by the generator getting it right.

These pin the two things that would bring it back: the emitted shape, and the key scheme that shape
depends on. The scheme is pinned against a title that is actually shipping, so the test fails if either
the hashing or the slugging drifts from what is already in strings.xml.
"""
import hashlib
import importlib.util
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("acg", ROOT / "Tools/appchangelog-gen.py")
acg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(acg)

# The 9.2.1 headline and the key it actually ships under, copied from values/strings.xml.
SHIPPED_TITLE = ("Battery saver quiets the gauges, translated Android notifications, "
                 "and instant chart loads")
SHIPPED_KEY = "l10n_app_changelog_battery_saver_quiets_the_gauges_translated_bdbe8650"


class TitleKeyTests(unittest.TestCase):

    def test_reproduces_a_key_that_is_actually_shipping(self):
        """Pinned against the real artifact, not against the function's own output."""
        self.assertEqual(SHIPPED_KEY, acg.title_key(SHIPPED_TITLE))

    def test_that_key_really_is_in_strings_xml(self):
        """If someone renames the key in the resources, this test's premise is gone — say so loudly
        rather than keep asserting against a string nothing uses."""
        xml = (ROOT / "android/app/src/main/res/values/strings.xml").read_text()
        self.assertIn(f'name="{SHIPPED_KEY}"', xml)

    def test_editing_the_title_mints_a_new_key(self):
        """The hash covers the exact title, so a reworded headline cannot silently inherit the old
        key — and with it, translations of different words."""
        self.assertNotEqual(acg.title_key(SHIPPED_TITLE), acg.title_key(SHIPPED_TITLE + " and more"))

    def test_slug_is_six_words_and_hash_is_eight_hex(self):
        key = acg.title_key("One two three four five six seven eight")
        self.assertTrue(key.startswith("l10n_app_changelog_one_two_three_four_five_six_"))
        self.assertRegex(key.rsplit("_", 1)[-1], r"^[0-9a-f]{8}$")

    def test_hash_is_of_the_title_text_itself(self):
        t = "Anything at all"
        self.assertTrue(acg.title_key(t).endswith(hashlib.sha1(t.encode()).hexdigest()[:8]))


class EscapingTests(unittest.TestCase):

    def test_apostrophe_is_backslashed_for_android(self):
        """An unescaped ' in a resource value is an aapt2 error, and release titles have them."""
        self.assertEqual(r"L\'économiseur", acg.esc_xml("L'économiseur"))

    def test_xml_entities(self):
        self.assertEqual("a &amp; b &lt;c&gt;", acg.esc_xml("a & b <c>"))

    def test_ampersand_is_escaped_before_the_others(self):
        """Escaping & last would double-escape the entities introduced by < and >."""
        self.assertEqual("&lt;a&gt; &amp; &lt;b&gt;", acg.esc_xml("<a> & <b>"))


class EmittedBlockTests(unittest.TestCase):

    WN = {"title": SHIPPED_TITLE, "date": "July 2026", "items": ["**One.** A thing."]}

    def test_kotlin_title_is_a_resource_reference(self):
        block = acg.kt_block("9.2.1", self.WN)
        self.assertIn(f"title = uiString(R.string.{SHIPPED_KEY})", block)

    def test_kotlin_title_is_not_a_literal(self):
        """The regression itself: `title = "…"` is what fails the gate."""
        block = acg.kt_block("9.2.1", self.WN)
        self.assertNotRegex(block, r'title\s*=\s*"')

    def test_swift_title_stays_a_literal(self):
        """SwiftUI auto-extracts into the catalog, so Apple needs no reference — and changing it
        would break the baseline that tracks these titles."""
        block = acg.sw_block("9.2.1", self.WN)
        self.assertIn(f'title: "{SHIPPED_TITLE}"', block)

    def test_items_stay_literals_on_both_platforms(self):
        """Only the title moved. Items are long-form prose the gate does not require extracting, and
        turning them into 60 resources per release was never the ask."""
        for block in (acg.kt_block("9.2.1", self.WN), acg.sw_block("9.2.1", self.WN)):
            self.assertIn('"**One.** A thing."', block)


class LocaleTargetTests(unittest.TestCase):

    def test_focus_locales_match_the_resource_dirs_on_disk(self):
        """A renamed or added locale dir must not leave the generator writing into nowhere."""
        for loc, d in acg.LOCALE_DIRS.items():
            self.assertTrue((ROOT / "android/app/src/main/res" / d / "strings.xml").is_file(),
                            f"{loc} -> {d}/strings.xml is missing")

    def test_english_source_is_the_values_dir(self):
        self.assertEqual("values", acg.LOCALE_DIRS["en"])


if __name__ == "__main__":
    unittest.main()
