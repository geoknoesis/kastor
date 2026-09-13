"""Regression tests for the release test-report gate (no JVM required)."""
import json
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class TestReportGate(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / "build/review/test-gate-checks"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def report(self, name, count, skipped=0, failed=0, declared=None):
        suite = ET.Element("testsuite", name=name, tests=str(count if declared is None else declared),
                           skipped=str(skipped), failures=str(failed), errors="0")
        for i in range(count):
            case = ET.SubElement(suite, "testcase", name=str(i), classname=name)
            if i < skipped:
                ET.SubElement(case, "skipped")
            elif i < skipped + failed:
                ET.SubElement(case, "failure")
        ET.ElementTree(suite).write(self.directory / f"TEST-{name}.xml", encoding="utf-8")

    def run_gate(self, *extra):
        return subprocess.run([sys.executable, str(ROOT / "scripts/check-test-results.py"),
                               str(self.directory), "--minimum", "13", "--require-suite", "NativeSuite", *extra],
                              capture_output=True, text=True)

    def allowlist(self, *entries):
        path = self.directory / "allowlist.json"
        path.write_text(json.dumps({"entries": [{"class": c, "test": t, "count": n} for c, t, n in entries]}),
                        encoding="utf-8")
        return str(path)

    def test_valid_native_and_ordinary_results_pass(self):
        self.report("OrdinarySuite", 12)
        self.report("NativeSuite", 1)
        self.assertEqual(0, self.run_gate().returncode)

    def test_extra_ordinary_tests_cannot_mask_missing_native_suite(self):
        self.report("OrdinarySuite", 20)
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_extra_ordinary_tests_cannot_mask_skipped_native_suite(self):
        self.report("OrdinarySuite", 20)
        self.report("NativeSuite", 1, skipped=1)
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_empty_required_suite_fails(self):
        self.report("OrdinarySuite", 20)
        self.report("NativeSuite", 0)
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_inflated_summary_count_fails(self):
        self.report("NativeSuite", 1, declared=20)
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_failed_case_fails(self):
        self.report("NativeSuite", 13, failed=1)
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_allowlisted_skip_passes(self):
        self.report("OrdinarySuite", 14, skipped=1)
        self.report("NativeSuite", 1)
        result = self.run_gate("--skip-allowlist", self.allowlist(("OrdinarySuite", "0", 1)))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_unlisted_skip_fails(self):
        self.report("OrdinarySuite", 14, skipped=1)
        self.report("NativeSuite", 1)
        result = self.run_gate("--skip-allowlist", self.allowlist(("OrdinarySuite", "other", 1)))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OrdinarySuite > 0", result.stderr)

    def test_skip_count_above_allowance_fails(self):
        self.report("OrdinarySuite", 14, skipped=1)
        self.report("DuplicateSuite", 1, skipped=1)
        (self.directory / "TEST-DuplicateSuite.xml").rename(self.directory / "TEST-DuplicateSuite-copy.xml")
        self.report("DuplicateSuite", 1, skipped=1)
        self.report("NativeSuite", 1)
        allowlist = self.allowlist(("OrdinarySuite", "0", 1), ("DuplicateSuite", "0", 1))
        self.assertNotEqual(0, self.run_gate("--skip-allowlist", allowlist).returncode)

    def test_allowlisted_test_that_now_runs_is_reported_not_failed(self):
        self.report("OrdinarySuite", 12)
        self.report("NativeSuite", 1)
        result = self.run_gate("--skip-allowlist", self.allowlist(("OrdinarySuite", "3", 1)))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("now execute", result.stdout)

    def test_committed_allowlist_is_well_formed(self):
        document = json.loads((ROOT / "scripts/test-skip-allowlist.json").read_text(encoding="utf-8"))
        keys = [(e["class"], e["test"]) for e in document["entries"]]
        self.assertEqual(len(keys), len(set(keys)))
        self.assertTrue(all(int(e["count"]) >= 1 for e in document["entries"]))


if __name__ == "__main__":
    unittest.main()
