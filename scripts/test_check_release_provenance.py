import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-release-provenance.py")
spec = importlib.util.spec_from_file_location("check_release_provenance", SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def run(event="push", status="completed", conclusion="success"):
    return {"event": event, "status": status, "conclusion": conclusion}


class CheckReleaseProvenanceTest(unittest.TestCase):
    def test_commit_on_main_passes(self):
        for status in ("identical", "behind"):
            self.assertIsNone(gate.check_ancestry({"status": status}))

    def test_commit_off_main_fails(self):
        for payload in ({"status": "ahead"}, {"status": "diverged"}, {}, None):
            self.assertIn("not an ancestor", gate.check_ancestry(payload))

    def test_successful_push_run_passes(self):
        self.assertIsNone(gate.check_ci_runs([run(conclusion="failure"), run()]))

    def test_no_runs_fails(self):
        self.assertIn("no ci.yml run", gate.check_ci_runs([]))

    def test_pull_request_run_alone_is_not_enough(self):
        self.assertIn("no ci.yml run", gate.check_ci_runs([run(event="pull_request")]))

    def test_failed_or_running_runs_fail(self):
        message = gate.check_ci_runs([run(conclusion="failure"), run(status="in_progress", conclusion=None)])
        self.assertIn("no successful", message)
        self.assertIn("in_progress/-", message)

    def test_malformed_response_fails(self):
        self.assertIn("unexpected", gate.check_ci_runs({"message": "Not Found"}))


if __name__ == "__main__":
    unittest.main()
