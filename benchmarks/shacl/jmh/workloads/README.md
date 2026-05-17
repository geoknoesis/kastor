# Workload descriptors (Tier A / B / C)

JSON files describe **which RDF to validate** for perf harnesses. Paths are **relative to the repository root** (the directory that contains `settings.gradle.kts`).

| Field | Meaning |
|-------|--------|
| `id` | Stable id for baselines / capability matrix rows |
| `tier` | `A` (conformance-adjacent), `B` (synthetic / stress), `C` (product / real-world) |
| `validationProfile` | Logical SHACL profile label (for documentation; runners map to `ValidationProfile`) |
| `comparisonMode` | Optional label for fairness (see design doc Section 12.1) |
| `dataTurtle` / `shapesTurtle` | Filesystem paths to Turtle inputs |
| `notes` | Human context |

**W3C-style cases** often use the **same** Turtle file for both `dataTurtle` and `shapesTurtle` when the manifest uses `sht:dataGraph <>` and `sht:shapesGraph <>` on one document — same as `Shacl12W3cCaseRunner`.

Harnesses (future JMH parametrization, external runners) should resolve paths with `Path(repoRoot).resolve(relativePath)`.
