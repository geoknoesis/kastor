"""Fail CI when tests fail, a supposedly populated suite silently disappears, or tests skip unexpectedly."""
import argparse
import collections
import json
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--minimum", type=int, required=True)
parser.add_argument("--require-suite", action="append", default=[], metavar="CLASS",
                    help="Require this suite to contain executed tests and no skips")
parser.add_argument("--skip-allowlist", type=Path, metavar="JSON",
                    help="Fail on skipped test cases not listed (class, test, count) in this file")
args = parser.parse_args()
if args.minimum < 1:
    parser.error("--minimum must be positive")
tests = skipped = failures = 0
required = {name: [0, 0] for name in args.require_suite}
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
        counts[0] += actual["tests"]
        counts[1] += actual["skipped"]
print(f"{tests} tests, {skipped} skipped, {failures} failed")
if failures or tests - skipped < args.minimum:
    raise SystemExit(f"Expected at least {args.minimum} executed tests with no failures")
for name, (count, skips) in required.items():
    if count == 0 or skips:
        raise SystemExit(f"Required suite missing, empty or skipped: {name}")
if args.skip_allowlist:
    document = json.loads(args.skip_allowlist.read_text(encoding="utf-8"))
    allowed = {(e["class"], e["test"]): int(e.get("count", 1)) for e in document["entries"]}
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
