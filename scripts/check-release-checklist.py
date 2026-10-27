#!/usr/bin/env python3
"""Fails when docs/reference/release-checklist.md states test floors that the workflows do not enforce.

The checklist's step 2 quotes the floors of `release-readiness.yml` ("overall N; RDF corpus N; SHACL module N with
`Shacl12NativeConformanceTest=N`"). The workflow is the source of truth; this gate compares the prose with it.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKLIST = "docs/reference/release-checklist.md"
READINESS = ".github/workflows/release-readiness.yml"

STEP_LINE = re.compile(
    r"overall ([\d,]+); RDF corpus ([\d,]+); SHACL module ([\d,]+) with `Shacl12NativeConformanceTest=(\d+)`")


def enforced_floors(workflow_text):
    """(overall, rdf corpus, shacl module, native suite) from the readiness workflow's three count checks."""
    minimums = [int(m) for m in re.findall(r"check-test-results\.py\s+\S+\s+--minimum\s+(\d+)", workflow_text)]
    suite = re.findall(r"Shacl12NativeConformanceTest=(\d+)", workflow_text)
    if len(minimums) != 3 or len(suite) != 1:
        raise ValueError(f"expected 3 --minimum values and 1 native suite floor in {READINESS}, "
                         f"found {minimums} and {suite}")
    return (*minimums, int(suite[0]))


def stated_floors(checklist_text):
    match = STEP_LINE.search(checklist_text)
    if not match:
        raise ValueError(f"{CHECKLIST} has no line of the form "
                         "'overall N; RDF corpus N; SHACL module N with `Shacl12NativeConformanceTest=N`'")
    return tuple(int(g.replace(",", "")) for g in match.groups())


def check_text(checklist_text, workflow_text):
    problems = []
    try:
        enforced, stated = enforced_floors(workflow_text), stated_floors(checklist_text)
    except ValueError as error:
        return [str(error)]
    labels = ("overall floor", "RDF corpus floor", "SHACL module floor", "native SHACL suite case count")
    for label, want, have in zip(labels, enforced, stated):
        if want != have:
            problems.append(f"{CHECKLIST} states {label} {have:,}, but {READINESS} enforces {want:,}")
    suite_mentions = {int(n) for n in re.findall(r"all (\d+) cases", checklist_text)}
    for n in sorted(suite_mentions - {enforced[3]}):
        problems.append(f"{CHECKLIST} says 'all {n} cases', but the enforced suite has {enforced[3]}")
    return problems


def main():
    problems = check_text((ROOT / CHECKLIST).read_text(encoding="utf-8"), (ROOT / READINESS).read_text(encoding="utf-8"))
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
