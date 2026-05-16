# TopBraid SHACL — self-hosted benchmarking

TopBraid / TopQuadrant tooling is **commercial**. There is no redistributable Docker image in this repo.

**Suggested approach:**

1. Use the same **three files** as [ERA-SHACL-Benchmark](../README.md) (data TTL, shapes TTL, report TTL) or the [workloads](../workloads/README.md) descriptors.
2. In a licensed environment, run TopBraid’s validation API or supported CLI with those inputs.
3. Emit **`Load time:`** and **`Validation time:`** in seconds (numeric only) to match ERA log parsing, or record timings in a separate column with an explicit **tooling path** label (Section 12.4.2 of the design doc).
4. Record **product edition**, **build**, and **comparison mode** in the published results table.

Do not commit proprietary binaries or license keys to this repository.
