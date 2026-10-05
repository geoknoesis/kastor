"""Release-provenance gate for publish.yml: the tagged commit must be on main and must have passed CI.

Both subcommands read the JSON printed by the GitHub CLI on stdin and exit non-zero with a message on failure.

    gh api "repos/$GITHUB_REPOSITORY/compare/main...$GITHUB_SHA" | python3 scripts/check-release-provenance.py ancestry
    gh run list --workflow ci.yml --commit "$GITHUB_SHA" --json status,conclusion,event \
        | python3 scripts/check-release-provenance.py ci

compare/main...SHA reports status "behind" when SHA is an ancestor of main and "identical" when it is main's tip.
"Ahead" or "diverged" means the tag points at a commit that is not on main.
"""
import json
import sys

ON_MAIN = {"identical", "behind"}


def check_ancestry(compare):
    """Return an error string, or None when the commit is reachable from main."""
    status = compare.get("status") if isinstance(compare, dict) else None
    if status in ON_MAIN:
        return None
    return (f"the tagged commit is not an ancestor of origin/main (compare status: {status!r}); "
            "tag a commit that has been merged to main")


def check_ci_runs(runs):
    """Return an error string, or None when a push-triggered ci.yml run for the commit succeeded."""
    if not isinstance(runs, list):
        return "unexpected response from `gh run list`"
    pushes = [run for run in runs if run.get("event") == "push"]
    if any(run.get("status") == "completed" and run.get("conclusion") == "success" for run in pushes):
        return None
    if not pushes:
        return "no ci.yml run triggered by a push was found for the tagged commit; CI must run on it before release"
    states = ", ".join(f"{run.get('status')}/{run.get('conclusion') or '-'}" for run in pushes)
    return f"no successful ci.yml run exists for the tagged commit (runs: {states})"


def main(argv):
    if len(argv) != 2 or argv[1] not in ("ancestry", "ci"):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        data = json.load(sys.stdin)
    except ValueError as error:
        print(f"::error::invalid JSON input: {error}")
        return 1
    error = check_ancestry(data) if argv[1] == "ancestry" else check_ci_runs(data)
    if error:
        print(f"::error::{error}")
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
