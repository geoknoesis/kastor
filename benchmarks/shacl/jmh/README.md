# SHACL performance benchmarks (JMH)

See [SHACL native engine benchmark design](../../../docs/kastor/design/shacl-native-engine-benchmark.md).

## Run

```bash
./gradlew :benchmarks:shacl:jmh
```

Results are printed to the console and written under `build/modules/benchmarks/shacl/results/jmh/` (paths follow the repo’s `build/modules/…` layout).

**CI:** `ci.yml` compiles these modules on every push/PR. Optional full runs: [.github/workflows/shacl-jmh.yml](../../../.github/workflows/shacl-jmh.yml) (`workflow_dispatch` or weekly).

### Tier A (JSON-driven W3C fixtures)

`NativeShaclTierAWorkloadBenchmark` reads descriptors from `workloads/tier-a/*.json` on the JMH classpath (`/tier-a/<name>.json`). Turtle paths in JSON are resolved from the **repository root** (walk up from `user.dir`, or set `-Dkastor.repo.root=...` when forking JMH).

### Tier A — Kastor vs Jena vs RDF4J vs PySHACL (ERA-style timings)

```bash
./gradlew :benchmarks:shacl:tierAEngineCompare
```

Runs the same Tier A Turtle workloads through **Kastor native**, **Apache Jena**, **Eclipse RDF4J** (`providerId = rdf4j`), and optionally **PySHACL** (requires Python + deps). This module depends on `:rdf:rdf4j` for the RDF4J leg.

### Kastor vs PySHACL (same inputs)

```bash
./gradlew :benchmarks:shacl:shaclCompareKastorPyTierA
```

This runs `shacl-era-cli` and `scripts/pyshacl/validate_era_style.py` on `minCount-001.ttl`. If Python or pyshacl is missing, the PySHACL leg is skipped with a message. Override the interpreter with `KASTOR_PYTHON` (e.g. `python3` or a venv).

## Layout

- `src/jmh/java` — JMH benchmark classes
- `src/jmh/resources/jmh-workload/` — tiny Turtle fixtures for micro-benchmarks
- `src/main/kotlin` — Kotlin helpers callable from JMH
- `workloads/` — Tier A/B/C JSON workload descriptors (paths relative to repo root)
- `baselines/` — regression baselines (see `baselines/README.md`)
- `capability/` — `matrix.v1.json` + README
- `scripts/pyshacl/` — ERA-style timings driver + pinned `requirements.txt`
- `docs/topbraid-self-hosted.md` — TopBraid benchmarking notes
