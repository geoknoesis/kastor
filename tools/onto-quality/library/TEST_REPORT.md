# `:tools:onto-quality` — test run report

This report summarizes the Gradle test task for **`onto-quality`**. Recreate locally with:

```bash
./gradlew :tools:onto-quality:test
```

PowerShell:

```powershell
.\gradlew.bat :tools:onto-quality:test
```

The Gradle HTML report is not checked in (it lives under the git-ignored build directory). After running the task above, open `build/modules/tools/onto-quality/reports/tests/test/index.html` (path relative to the repository root); JUnit XML results are in `build/modules/tools/onto-quality/test-results/test/`.

This file does not record per-run test counts; they change as tests are added, so read them from the generated report.

## Suite notes

- **`ModernEngineeringTest`** — Parameterized checks that pitfalls **N03, N04, N06, N07, N09, N16, N17, N20, N23, N26, N32, N34** are reported against `src/test/resources/fixtures/modern-engineering-fixture.ttl` using `BundledCatalogs.MODERN_ENGINEERING` only.

- **`ModernRdf12Test`** — **N28** and **N29** on `fixtures/rdf12-fixture.ttl`; **N30** asserted only via presence of `NoTripleTermInSubjectShape` in the bundled TTL (cannot be exercised from Turtle fixtures when parsers reject triple-term subjects).

- **`OopsCalibrationTest`** — Structural calibration against **`oops-corpus/`** (OOPS-aligned). **One** case is intentionally **skipped**: **P09** (no upstream corpus file in-tree), per assumptions in that test.

- **`OopsCalibrationTest.SemanticTierAfterEnrichment`** — Embedding-backed semantic tier (**P02, P12, P21, P32**) when ONNX enrichment tests are enabled. This run executed all **four** parameterized cases (**0 skipped**).

- **`QualityCheckerTest`** — Regression on `test-ontologies/zoo-with-pitfalls.ttl` for **`OWL_QUALITY`** (P06, P09, P34).

- **`OopsBenchmarkTest`** — Both tests **skipped by default** (benchmark harness; typically enabled with `KASTOR_OOPS_BENCHMARK=1` per [CALIBRATION.md](./CALIBRATION.md)).

## CI hint

Semantic / ONNX-heavy tests respect **`KASTOR_SKIP_EMBEDDING_TESTS=1`**. When unset (as on this verified run when semantic nested class ran successfully), ONNX and model downloads may be required — see **`README.md`**.
