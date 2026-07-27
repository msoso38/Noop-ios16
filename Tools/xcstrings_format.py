"""Canonical, stable JSON writer for `.xcstrings` String Catalogs.

Xcode rewrites the *entire* catalog file every time it syncs the catalog
against source during a local build (SWIFT_EMIT_LOC_STRINGS): `" : "`
spacing on both sides of the colon, no trailing newline, and keys in
whatever order its own Dictionary encoding happens to produce (empirically,
not alphabetical and not any locale collation — believed to be Swift's
hash-seeded Dictionary iteration order, which this tooling cannot
reproduce). That means a real Xcode build will still reorder the file in
your working tree no matter what wrote it last — that part isn't fixable
from here.

What this module gives you instead is a *stable* canonical form (keys
sorted, same colon spacing and no-trailing-newline convention Xcode uses)
that `seed-string-catalog.py` / `translate-de.py` / `translate-it.py` write,
and that a pre-commit hook (see Tools/git-hooks/pre-commit) re-applies to
any staged catalog. Because it's deterministic, committed history only ever
shows real content changes between commits — and running this module's CLI
on a catalog Xcode just rewrote collapses the local diff back down to real
changes too, for a quick "did anything actually change" check.
"""
import json
import sys
from pathlib import Path


def _dump(obj, level: int) -> str:
    if isinstance(obj, dict):
        indent = "  " * level
        child_indent = "  " * (level + 1)
        # Xcode never collapses an empty object to "{}" — it emits a blank
        # line between the braces, same as its non-empty formatting would
        # produce with zero joined items. Matching that byte-for-byte is
        # required for round-tripping a catalog Xcode already touched
        # without introducing a spurious diff.
        if not obj:
            return "{\n\n" + indent + "}"
        items = [
            f"{child_indent}{json.dumps(key, ensure_ascii=False)} : {_dump(value, level + 1)}"
            for key, value in sorted(obj.items())
        ]
        return "{\n" + ",\n".join(items) + "\n" + indent + "}"
    return json.dumps(obj, ensure_ascii=False)


def dumps_catalog(catalog: dict) -> str:
    return _dump(catalog, 0)


def write_catalog(path: Path, catalog: dict) -> None:
    path.write_text(dumps_catalog(catalog), encoding="utf-8")


def canonicalize(path: Path) -> bool:
    """Rewrite `path` in canonical form. Returns whether it actually changed."""
    original = path.read_text(encoding="utf-8")
    catalog = json.loads(original)
    canonical = dumps_catalog(catalog)
    if canonical == original:
        return False
    path.write_text(canonical, encoding="utf-8")
    return True


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: xcstrings_format.py <file.xcstrings> [...]", file=sys.stderr)
        return 2
    changed = 0
    for arg in argv:
        path = Path(arg)
        if canonicalize(path):
            print(f"canonicalized: {path}")
            changed += 1
        else:
            print(f"already canonical: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
