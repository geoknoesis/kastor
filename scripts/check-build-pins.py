"""Fail when literal build-script pins drift from the version catalog.

`build.gradle.kts` pins `org.jetbrains.kotlin:kotlin-metadata-jvm` in its buildscript classpath because
dependency-analysis bundles a kotlin-metadata-jvm that cannot read Kotlin 2.4 metadata. Version catalogs
are not available inside `buildscript {}`, so the literal must be kept equal to `kotlin` in
gradle/libs.versions.toml by hand. This check makes that automatic (run in CI).

Usage: python scripts/check-build-pins.py [repository-root]
"""
import re
import sys
from pathlib import Path

# (description, build file, regex with group "v", catalog version key)
PINS = [
    ("kotlin-metadata-jvm buildscript pin", "build.gradle.kts",
     re.compile(r"org\.jetbrains\.kotlin:kotlin-metadata-jvm:(?P<v>[^\"')\s]+)"), "kotlin"),
]


def catalog_version(toml_text, key):
    """Return the value of `key = "..."` inside the [versions] table, or None."""
    in_versions = False
    for raw in toml_text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if line.startswith("["):
            in_versions = line == "[versions]"
            continue
        if in_versions:
            match = re.fullmatch(rf'{re.escape(key)}\s*=\s*"([^"]+)"', line)
            if match:
                return match.group(1)
    return None


def check(root):
    """Return a list of problem descriptions (empty when every pin matches)."""
    root = Path(root)
    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    problems = []
    for description, build_file, pattern, key in PINS:
        expected = catalog_version(catalog, key)
        if expected is None:
            problems.append(f"gradle/libs.versions.toml: [versions] has no `{key}` entry")
            continue
        path = root / build_file
        text = path.read_text(encoding="utf-8") if path.is_file() else ""
        matches = list(pattern.finditer(text))
        if not matches:
            # A removed pin is fine (e.g. once dependency-analysis supports the Kotlin metadata version).
            continue
        for match in matches:
            if match.group("v") != expected:
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{build_file}:{line}: {description} is {match.group('v')}, "
                                f"but the catalog `{key}` is {expected}")
    return problems


def main(argv):
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[1]
    problems = check(root)
    if problems:
        print("Build pins differ from gradle/libs.versions.toml:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    print("Build pins match gradle/libs.versions.toml")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
