"""Exercise performance-gate acceptance and rejection through its command-line interface."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def report():
    return [{
        "benchmark": "example.Search", "params": {"size": "100"},
        "jmhVersion": "1.37", "jvm": "java", "jdkVersion": "21", "vmName": "OpenJDK",
        "vmVersion": "21", "jvmArgs": [], "mode": "avgt", "threads": 1, "forks": 2,
        "warmupIterations": 3, "measurementIterations": 5, "warmupTime": "1 s",
        "measurementTime": "1 s", "warmupBatchSize": 1, "measurementBatchSize": 1,
        "primaryMetric": {"score": 100.0, "scoreUnit": "ms/op"},
        "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 100.0, "scoreUnit": "B/op"}},
    }]


class PerformanceGate(unittest.TestCase):
    def run_gate(self, before, after):
        scratch = ROOT / "build/review/performance-gate-checks"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            paths = [Path(directory) / name for name in ("before.json", "after.json")]
            for path, value in zip(paths, (before, after)):
                path.write_text(json.dumps(value), encoding="utf-8")
            return subprocess.run([sys.executable, str(ROOT / "scripts/check-performance.py"),
                                   *map(str, paths)], capture_output=True, text=True)

    def test_identical_complete_reports_pass(self):
        result = self.run_gate(report(), report())
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_run_setting_in_both_reports_fails(self):
        value = report()
        del value[0]["forks"]
        self.assertNotEqual(0, self.run_gate(value, value).returncode)

    def test_absent_allocation_in_both_reports_fails(self):
        value = report()
        value[0]["secondaryMetrics"] = {}
        self.assertNotEqual(0, self.run_gate(value, value).returncode)

    def test_batch_size_mismatch_fails(self):
        after = report()
        after[0]["measurementBatchSize"] = 10
        self.assertNotEqual(0, self.run_gate(report(), after).returncode)

    def test_latency_and_allocation_regressions_fail_independently(self):
        for allocation in (False, True):
            with self.subTest(allocation=allocation):
                after = report()
                metric = (after[0]["secondaryMetrics"]["gc.alloc.rate.norm"] if allocation
                          else after[0]["primaryMetric"])
                metric["score"] = 116
                self.assertNotEqual(0, self.run_gate(report(), after).returncode)

    def test_throughput_uses_opposite_direction(self):
        before = report()
        before[0]["mode"] = "thrpt"
        before[0]["primaryMetric"]["scoreUnit"] = "ops/s"
        after = copy.deepcopy(before)
        after[0]["primaryMetric"]["score"] = 120
        self.assertEqual(0, self.run_gate(before, after).returncode)
        after[0]["primaryMetric"]["score"] = 84
        self.assertNotEqual(0, self.run_gate(before, after).returncode)

    def test_missing_or_duplicate_cases_fail(self):
        for after in ([], report() * 2):
            self.assertNotEqual(0, self.run_gate(report(), after).returncode)

    def test_nonfinite_or_incompatible_metrics_fail(self):
        for field, value in (("score", float("nan")), ("score", float("inf")),
                             ("score", -1), ("scoreUnit", "ns/op")):
            with self.subTest(field=field, value=value):
                after = report()
                after[0]["primaryMetric"][field] = value
                self.assertNotEqual(0, self.run_gate(report(), after).returncode)


if __name__ == "__main__":
    unittest.main()
