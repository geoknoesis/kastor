# `onto-quality-metrics` v0.1 — calibration notes

This document records cross-implementation comparison intent for the OQuaRE-aligned
metrics in `onto-quality-metrics`, mirroring the calibration pattern used by
[`../library/CALIBRATION.md`](../library/CALIBRATION.md).

Calibration date: **2026-05-17** (initial module landing).

## Reference implementation

Primary external comparator: [tecnomod-um/oquare-metrics](https://github.com/tecnomod-um/oquare-metrics)
(Java implementation aligned with the OQuaRE literature).

## Fixtures under `src/test/resources/cross-impl/`

| File | Purpose |
|------|---------|
| `minimal-branches.ttl` | Tiny ontology with one subclass pair plus one object-property axiom between named classes (non-inheritance edge for richness metrics). |
| `populated-restrictions.ttl` | Classes with instances, annotations, a datatype attribute and an `owl:Restriction`. |
| `pizza-like.ttl` | Externally derived fixture: a 10-class pizza-like ontology with multiple inheritance, an isolated class, domain/range properties and a restriction. All 15 raw OQuaRE values and five scores were worked out by hand from the OQuaRE definitions (derivation in the test KDoc), not by running the calculators. |

All three fixtures are asserted exactly (tolerance 1e-9) in `CrossImplementationCalibrationTest`.

## Observed Kastor outputs (logged only in v0.1)

The test [`CrossImplementationCalibrationTest`](src/test/kotlin/com/geoknoesis/kastor/ontoquality/metrics/CrossImplementationCalibrationTest.kt)
loads each cross-impl fixture and logs raw OQuaRE metrics at **INFO**.

**v0.1 policy:** differences versus `tecnomod-um/oquare-metrics` are **not**
asserted in CI. When that tool is run locally on the same TTL, paste numbers
into the table below and note any drift > 5% for follow-up.

| Fixture | Compared | DITOnto | NOCOnto | RROnto | INROnto | Notes |
|---------|----------|---------|---------|--------|---------|-------|
| minimal-branches.ttl | pending manual run | (see logs) | … | … | … | Run `./gradlew :tools:onto-quality-metrics:test --tests "*CrossImplementationCalibrationTest*" --info` for Kastor values |

## Kastor metrics vocabulary

Stable Turtle vocabulary: [`src/main/resources/vocab/kastor-metrics.ttl`](src/main/resources/vocab/kastor-metrics.ttl)

Parsed in [`KastorMetricsVocabularyTest`](src/test/kotlin/com/geoknoesis/kastor/ontoquality/metrics/KastorMetricsVocabularyTest.kt).
