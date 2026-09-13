"""Fail CI when tests fail or a supposedly populated suite silently disappears."""
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--minimum", type=int, required=True)
parser.add_argument("--require-suite", action="append", default=[], metavar="CLASS",
                    help="Require this suite to contain executed tests and no skips")
args = parser.parse_args()
if args.minimum < 1:
    parser.error("--minimum must be positive")
tests = skipped = failures = 0
required = {name: [0, 0] for name in args.require_suite}
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
