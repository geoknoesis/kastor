"""Regression tests for the release test-report gate (no JVM required)."""
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

    def run_gate(self):
        return subprocess.run([sys.executable, str(ROOT / "scripts/check-test-results.py"),
                               str(self.directory), "--minimum", "13", "--require-suite", "NativeSuite"],
                              capture_output=True, text=True)

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


if __name__ == "__main__":
    unittest.main()
