"""Fail CI when tests fail, a supposedly populated suite silently disappears, or tests skip unexpectedly.

    python scripts/check-test-results.py DIR --minimum N [--require-suite CLASS[=MIN] ...] [--skip-allowlist JSON]

A required suite must exist and execute at least MIN tests (default 1). Its skipped cases fail the gate
unless they are listed in the skip allowlist, so an allowlisted known deviation does not mask a suite that
silently fell back to a small bundled fixture (use MIN for that). Every allowlist entry needs a non-empty,
machine-independent reason.
"""
import argparse
import collections
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def required_suite(value):
    name, _, minimum = value.partition("=")
    if not name:
        raise argparse.ArgumentTypeError("suite name must not be empty")
    try:
        count = int(minimum) if minimum else 1
    except ValueError:
        raise argparse.ArgumentTypeError(f"minimum for {name} must be an integer: {minimum}")
    if count < 1:
        raise argparse.ArgumentTypeError(f"minimum for {name} must be positive")
    return name, count


parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--minimum", type=int, required=True)
parser.add_argument("--require-suite", action="append", default=[], metavar="CLASS[=MIN]", type=required_suite,
                    help="Require this suite to execute at least MIN tests (default 1) and have no unlisted skips")
parser.add_argument("--skip-allowlist", type=Path, metavar="JSON",
                    help="Fail on skipped test cases not listed (class, test, count) in this file")
args = parser.parse_args()
if args.minimum < 1:
    parser.error("--minimum must be positive")

# Reasons are published evidence: they must explain the skip and must not leak a developer's machine layout.
MACHINE_PATH = re.compile(r"file:/|(?<![A-Za-z])[A-Za-z]:[\\/]|/home/[^/\s]+/|/Users/[^/\s]+/")
allowed = {}
if args.skip_allowlist:
    document = json.loads(args.skip_allowlist.read_text(encoding="utf-8"))
    invalid = sorted(f"{e.get('class')} > {e.get('test')}" for e in document["entries"]
                     if not str(e.get("reason", "")).strip() or MACHINE_PATH.search(str(e.get("reason", ""))))
    if invalid:
        raise SystemExit(f"{len(invalid)} allowlist entr(y/ies) in {args.skip_allowlist} need a non-empty reason "
                         "without absolute machine paths:\n  " + "\n  ".join(invalid))
    allowed = {(e["class"], e["test"]): int(e.get("count", 1)) for e in document["entries"]}

tests = skipped = failures = 0
required = {name: {"minimum": minimum, "tests": 0, "skipped": 0, "unlisted": []}
            for name, minimum in args.require_suite}
skipped_cases = collections.Counter()
executed_cases = set()
for path in args.directory.rglob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    if suite.tag != "testsuite":
        raise SystemExit(f"Expected a Gradle testsuite report: {path}")
    cases = suite.findall("testcase")
    actual = {"tests": len(cases), "skipped": sum(c.find("skipped") is not None for c in cases),
              "failures": sum(c.find("failure") is not None for c in cases),
              "errors": sum(c.find("error") is not None for c in cases)}
    if any(int(suite.get(key, 0)) != count for key, count in actual.items()):
        raise SystemExit(f"Report totals do not match test cases: {path}")
    tests += actual["tests"]
    skipped += actual["skipped"]
    failures += actual["failures"] + actual["errors"]
    for case in cases:
        key = (case.get("classname", ""), case.get("name", ""))
        if case.find("skipped") is not None:
            skipped_cases[key] += 1
        else:
            executed_cases.add(key)
    if suite.get("name") in required:
        counts = required[suite.get("name")]
        counts["tests"] += actual["tests"]
        counts["skipped"] += actual["skipped"]
        counts["unlisted"] += [key for key in ((c.get("classname", ""), c.get("name", "")) for c in cases
                                               if c.find("skipped") is not None) if key not in allowed]
print(f"{tests} tests, {skipped} skipped, {failures} failed")
if failures or tests - skipped < args.minimum:
    raise SystemExit(f"Expected at least {args.minimum} executed tests with no failures")
for name, counts in required.items():
    executed = counts["tests"] - counts["skipped"]
    if counts["tests"] == 0 or executed < counts["minimum"] or counts["unlisted"]:
        raise SystemExit(f"Required suite missing, too small or skipped: {name} (executed {executed}, "
                         f"minimum {counts['minimum']}, unlisted skips {len(counts['unlisted'])})")
    print(f"Required suite {name}: {executed} executed, {counts['skipped']} allowlisted skips")
if args.skip_allowlist:
    unexpected = sorted(f"{cls} > {name} (skipped {n}, allowed {allowed.get((cls, name), 0)})"
                        for (cls, name), n in skipped_cases.items() if n > allowed.get((cls, name), 0))
    now_running = sorted(f"{cls} > {name}" for (cls, name) in allowed
                         if (cls, name) in executed_cases and (cls, name) not in skipped_cases)
    if now_running:
        print(f"Allowlisted skips that now execute ({len(now_running)}); consider removing them from "
              f"{args.skip_allowlist}:")
        for line in now_running:
            print(f"  {line}")
    if unexpected:
        raise SystemExit(f"{len(unexpected)} skipped test(s) not in {args.skip_allowlist}:\n  " +
                         "\n  ".join(unexpected))
