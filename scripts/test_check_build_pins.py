import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-build-pins.py")
spec = importlib.util.spec_from_file_location("check_build_pins", SCRIPT)
pins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pins)

CATALOG = """[versions]
# comment
kotlin = "2.4.20"
ksp = "2.4.20-1.0.0"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
"""


def repo(directory, build, catalog=CATALOG):
    root = Path(directory)
    (root / "gradle").mkdir()
    (root / "gradle/libs.versions.toml").write_text(catalog, encoding="utf-8")
    if build is not None:
        (root / "build.gradle.kts").write_text(build, encoding="utf-8")
    return root


class CheckBuildPinsTest(unittest.TestCase):
    def test_matching_pin_passes(self):
        with tempfile.TemporaryDirectory() as d:
            root = repo(d, 'buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20") } }')
            self.assertEqual(pins.check(root), [])

    def test_drifted_pin_fails_with_location(self):
        with tempfile.TemporaryDirectory() as d:
            root = repo(d, 'buildscript {\n  classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")\n}')
            problems = pins.check(root)
            self.assertEqual(len(problems), 1)
            self.assertIn("build.gradle.kts:2", problems[0])
            self.assertIn("2.4.10", problems[0])
            self.assertIn("2.4.20", problems[0])
            self.assertEqual(pins.main(["x", str(root)]), 1)

    def test_removed_pin_passes(self):
        with tempfile.TemporaryDirectory() as d:
            root = repo(d, "plugins { }")
            self.assertEqual(pins.check(root), [])
            self.assertEqual(pins.main(["x", str(root)]), 0)

    def test_missing_catalog_key_fails(self):
        with tempfile.TemporaryDirectory() as d:
            root = repo(d, 'classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")', catalog='[versions]\nksp = "1"\n')
            self.assertIn("no `kotlin` entry", pins.check(root)[0])

    def test_catalog_key_outside_versions_table_is_ignored(self):
        text = '[libraries]\nkotlin = "9.9.9"\n[versions]\nkotlin = "2.4.20"\n'
        self.assertEqual(pins.catalog_version(text, "kotlin"), "2.4.20")
        self.assertIsNone(pins.catalog_version('[plugins]\nkotlin = "1"\n', "kotlin"))

    def test_real_repository_is_consistent(self):
        self.assertEqual(pins.check(SCRIPT.resolve().parents[1]), [])


if __name__ == "__main__":
    unittest.main()
