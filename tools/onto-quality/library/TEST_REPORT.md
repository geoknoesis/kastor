# `:tools:onto-quality` — test run report

This report summarizes the Gradle test task for **`onto-quality`**. Recreate locally with:

```bash
./gradlew :tools:onto-quality:test
```

PowerShell:

```powershell
.\gradlew.bat :tools:onto-quality:test
```

Gradle HTML summary (machine-local): [`build/modules/tools/onto-quality/reports/tests/test/index.html`](../../../build/modules/tools/onto-quality/reports/tests/test/index.html) (path relative to repo root).

## Last verified run

| Field | Value |
|--------|--------|
| Timestamp (build) | **2026-05-16T00:16:29Z–00:16:37Z** (UTC, from JUnit XML `timestamp`) |
| Task | `:tools:onto-quality:test` |
| Gradle | `--rerun-tasks` + `--no-daemon` |
| Overall | **PASS** (`BUILD SUCCESSFUL`) |

### Aggregates (from JUnit `testsuite`)

| Suite | Tests | Failures | Errors | Skipped |
|-------|------:|---------:|-------:|--------:|
| `ModernEngineeringTest` | 12 | 0 | 0 | 0 |
| `ModernRdf12Test` | 3 | 0 | 0 | 0 |
| `OopsCalibrationTest` | 25 | 0 | 0 | 1 |
| `OopsCalibrationTest.SemanticTierAfterEnrichment` | 4 | 0 | 0 | 0 |
| `QualityCheckerTest` | 1 | 0 | 0 | 0 |
| `OopsBenchmarkTest` | 2 | 0 | 0 | 2 |
| **Totals** | **47** | **0** | **0** | **3** |

## Suite notes

- **`ModernEngineeringTest`** — Parameterized checks that pitfalls **N03, N04, N06, N07, N09, N16, N17, N20, N23, N26, N32, N34** are reported against `src/test/resources/fixtures/modern-engineering-fixture.ttl` using `BundledCatalogs.MODERN_ENGINEERING` only.

- **`ModernRdf12Test`** — **N28** and **N29** on `fixtures/rdf12-fixture.ttl`; **N30** asserted only via presence of `NoTripleTermInSubjectShape` in the bundled TTL (cannot be exercised from Turtle fixtures when parsers reject triple-term subjects).

- **`OopsCalibrationTest`** — Structural calibration against **`oops-corpus/`** (OOPS-aligned). **One** case is intentionally **skipped**: **P09** (no upstream corpus file in-tree), per assumptions in that test.

- **`OopsCalibrationTest.SemanticTierAfterEnrichment`** — Embedding-backed semantic tier (**P02, P12, P21, P32**) when ONNX enrichment tests are enabled. This run executed all **four** parameterized cases (**0 skipped**).

- **`QualityCheckerTest`** — Regression on `test-ontologies/zoo-with-pitfalls.ttl` for **`OWL_QUALITY`** (P06, P09, P34).

- **`OopsBenchmarkTest`** — Both tests **skipped by default** (benchmark harness; typically enabled with `KASTOR_OOPS_BENCHMARK=1` per [CALIBRATION.md](./CALIBRATION.md)).

## CI hint

Semantic / ONNX-heavy tests respect **`KASTOR_SKIP_EMBEDDING_TESTS=1`**. When unset (as on this verified run when semantic nested class ran successfully), ONNX and model downloads may be required — see **`README.md`**.
