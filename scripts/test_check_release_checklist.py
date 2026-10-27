import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-release-checklist.py")
spec = importlib.util.spec_from_file_location("check_release_checklist", SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

WORKFLOW = """
  python scripts/check-test-results.py build/modules --minimum 5800 --skip-allowlist a.json
  python scripts/check-test-results.py build/modules/rdf/conformance/test-results/test --minimum 1845 --skip-allowlist a.json
  python scripts/check-test-results.py build/modules/rdf/shacl-validation/test-results/test --minimum 460 \
    --require-suite "com.x.Shacl12NativeConformanceTest=166" \
"""
CHECKLIST = "(overall 5,800; RDF corpus 1,845; SHACL module 460 with `Shacl12NativeConformanceTest=166`) all 166 cases"


class CheckReleaseChecklistTest(unittest.TestCase):
    def test_matching_floors_pass(self):
        self.assertEqual(gate.check_text(CHECKLIST, WORKFLOW), [])

    def test_each_stale_floor_is_reported(self):
        stale = CHECKLIST.replace("5,800", "3,500").replace("1,845", "1,700").replace("460", "285")
        self.assertEqual(len(gate.check_text(stale, WORKFLOW)), 3)

    def test_stale_suite_count_is_reported(self):
        self.assertEqual(len(gate.check_text(CHECKLIST.replace("=166", "=150"), WORKFLOW)), 1)
        self.assertEqual(len(gate.check_text(CHECKLIST.replace("all 166", "all 150"), WORKFLOW)), 1)

    def test_missing_statement_or_workflow_shape_is_reported(self):
        self.assertEqual(len(gate.check_text("no floors here", WORKFLOW)), 1)
        self.assertEqual(len(gate.check_text(CHECKLIST, "nothing")), 1)

    def test_repository_checklist_matches_the_workflow(self):
        self.assertEqual(gate.main(), 0)


if __name__ == "__main__":
    unittest.main()
