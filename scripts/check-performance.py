"""Compare JMH JSON results on the same runner/JDK/options; fail on missing cases.

Example: python scripts/check-performance.py baseline.json candidate.json
Use --max-regression 0.15 for a 15% latency/allocation regression budget.
Hardware/JDK equivalence must be established by the calling job.
Both reports must include GC allocation profiling and complete JMH run settings.
"""
import argparse
import json
import math

RUN_FIELDS = ("jmhVersion", "jvm", "jdkVersion", "vmName", "vmVersion", "jvmArgs",
              "mode", "threads", "forks", "warmupIterations", "measurementIterations",
              "warmupTime", "measurementTime", "warmupBatchSize", "measurementBatchSize")


def cases(path):
    with open(path, encoding="utf-8") as stream:
        values = json.load(stream)
    result = {}
    for value in values:
        missing = [field for field in RUN_FIELDS if field not in value or value[field] is None]
        if missing:
            raise ValueError(f"Incomplete JMH run settings: {missing}")
        if value["mode"] not in ("avgt", "thrpt", "sample", "ss"):
            raise ValueError(f"Unsupported JMH mode: {value['mode']}")
        if "gc.alloc.rate.norm" not in value.get("secondaryMetrics", {}):
            raise ValueError("Missing GC allocation metric; run JMH with -prof gc")
        key = (value["benchmark"], tuple(sorted(value.get("params", {}).items())))
        if key in result:
            raise ValueError(f"Duplicate benchmark case: {key}")
        result[key] = value
    if not result:
        raise ValueError("Empty benchmark report")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--max-regression", type=float, default=0.15)
    args = parser.parse_args()
    if not 0 <= args.max_regression < 1:
        parser.error("--max-regression must be in [0, 1)")
    before, after = cases(args.baseline), cases(args.candidate)
    failures = []
    for key, old in before.items():
        new = after.get(key)
        if new is None:
            failures.append(f"Missing case: {key}")
            continue
        for field in RUN_FIELDS:
            if old.get(field) != new.get(field):
                failures.append(f"Incomparable {field}: {key}")
        metrics = [("primaryMetric", old["primaryMetric"], new["primaryMetric"], old["mode"] == "thrpt")]
        allocation = "gc.alloc.rate.norm"
        if allocation in old.get("secondaryMetrics", {}):
            if allocation not in new.get("secondaryMetrics", {}):
                failures.append(f"Missing allocation metric: {key}")
            else:
                metrics.append((allocation, old["secondaryMetrics"][allocation], new["secondaryMetrics"][allocation], False))
        for name, a, b, higher_better in metrics:
            if a["scoreUnit"] != b["scoreUnit"] or not all(math.isfinite(x["score"]) and x["score"] >= 0 for x in (a, b)):
                failures.append(f"Invalid/incompatible metric {name}: {key}")
                continue
            allowed = a["score"] * (1 - args.max_regression if higher_better else 1 + args.max_regression)
            regressed = b["score"] < allowed if higher_better else b["score"] > allowed
            print(f"{key}: {name} {a['score']:.4g} -> {b['score']:.4g} {b['scoreUnit']}")
            if regressed:
                failures.append(f"Regression in {name}: {key}")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"PASS: {len(before)} cases within {args.max_regression:.0%} regression budget")


if __name__ == "__main__":
    main()
