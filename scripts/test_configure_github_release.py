import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("configure-github-release.sh")
BASH = shutil.which("bash")

# A fake `gh` that answers only read-only calls. It prints the already-filtered output the script's --jq would
# produce, so the test needs no network and cannot change GitHub. Any non-GET call fails the test.
FAKE_GH = r"""#!/usr/bin/env bash
args="$*"
case "$args" in
  *"-X "*) echo "UNEXPECTED WRITE: $args" >&2; exit 99 ;;
  "repo view"*) echo owner/repo ;;
  "api users/"*) echo 1 ;;
  "api repos/owner/repo/rulesets/7"*) printf '%s' "$FAKE_CHECKS" ;;
  "api repos/owner/repo/rulesets "*) echo 7 ;;
  *"deployment-branch-policies"*) echo 1 ;;
  *"dependency-graph/sbom"*) exit 1 ;;
  *"/pages"*) echo workflow ;;
  *) exit 1 ;;
esac
"""


@unittest.skipUnless(BASH, "bash is required")
class ConfigureGithubReleaseTest(unittest.TestCase):
    def dry_run(self, current_checks, *flags):
        with tempfile.TemporaryDirectory() as scratch:
            fake = Path(scratch, "gh")
            fake.write_text(FAKE_GH, encoding="utf-8", newline="\n")
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            env = dict(os.environ, PATH=scratch + os.pathsep + os.environ["PATH"],
                       FAKE_CHECKS="".join(c + "\n" for c in current_checks))
            result = subprocess.run([BASH, str(SCRIPT), "--reviewer", "me", *flags], env=env,
                                    capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    LIVE = ["build (ubuntu-latest)", "build (windows-latest)", "shacl-w3c-suite", "macos"]

    def test_default_run_keeps_macos_and_warns_of_nothing(self):
        out = self.dry_run(self.LIVE)
        self.assertIn('"macos"', out.split("required checks:")[1].splitlines()[0])
        self.assertNotIn("WARNING", out)
        self.assertIn('{"context":"macos"}', out)

    def test_macos_is_required_even_when_the_ruleset_lacks_it(self):
        out = self.dry_run(self.LIVE[:3])
        self.assertIn('{"context":"macos"}', out)

    def test_no_macos_opt_out_warns_in_the_dry_run(self):
        out = self.dry_run(self.LIVE, "--no-macos")
        self.assertIn('WARNING: applying REMOVES these currently required checks: "macos"', out)
        self.assertNotIn('{"context":"macos"}', out)

    def test_checks_the_script_does_not_manage_are_kept(self):
        out = self.dry_run(self.LIVE + ["codeql"])
        self.assertIn('{"context":"codeql"}', out)
        self.assertNotIn("WARNING", out)

    def test_dependency_review_stays_required_in_auto_mode_when_already_required(self):
        out = self.dry_run(self.LIVE + ["dependency-review"])
        self.assertIn('{"context":"dependency-review"}', out)
        out = self.dry_run(self.LIVE + ["dependency-review"], "--no-dependency-review")
        self.assertIn('WARNING: applying REMOVES these currently required checks: "dependency-review"', out)


if __name__ == "__main__":
    unittest.main()
