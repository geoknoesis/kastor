#!/usr/bin/env python3
"""
ERA-SHACL-Benchmark compatible driver for PySHACL.

Usage:
  python validate_era_style.py <data.ttl> <shapes.ttl> <report.ttl>

Stdout (two numeric lines, seconds — parsed by ERA run_benchmark.sh):
  Data graph size: <triple count>
  Load time: <seconds>
  Validation time: <seconds>

Warm vs cold: each process invocation pays interpreter + imports; use a long-lived
worker if you need steady-state JVM-style comparisons.
"""
from __future__ import annotations

import sys
import time

from pyshacl import validate
from rdflib import Graph


def main() -> None:
    if len(sys.argv) != 4:
        print("Usage: validate_era_style.py <data.ttl> <shapes.ttl> <report.ttl>", file=sys.stderr)
        sys.exit(2)

    data_path, shapes_path, report_path = sys.argv[1:4]

    t0 = time.perf_counter()
    data_g = Graph()
    data_g.parse(data_path, format="turtle")
    load_s = time.perf_counter() - t0
    print(f"Data graph size: {len(data_g)}")
    print(f"Load time: {load_s}")

    shapes_g = Graph()
    shapes_g.parse(shapes_path, format="turtle")

    t1 = time.perf_counter()
    conforms, report_g, _text = validate(
        data_graph=data_g,
        shacl_graph=shapes_g,
        inference="none",
        abort_on_first=False,
    )
    val_s = time.perf_counter() - t1
    print(f"Validation time: {val_s}")

    report_g.serialize(destination=report_path, format="turtle")
    _ = conforms  # report file is authoritative for tooling


if __name__ == "__main__":
    main()
