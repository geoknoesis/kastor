# Candidate compatibility review

This compares the pre-hardening local staging build with the current candidate. It is **not** a comparison against a confirmed previous public release. The repository tag alone does not establish which binary artifacts users consumed.

## Reviewed changes

- Existing isomorphism, model-download, similarity-search and semantic-enricher entry points remain; explicit limit/deadline overloads are additive.
- New Gradle task file properties have concrete defaults. Existing custom task subclasses do not acquire new abstract getter requirements.
- Jena 6.2.0 and Thrift 0.24.0 must remain aligned; persistence tests cover closing and reopening stores with inference.
- Kotlin is upgraded to 2.4.20 and KSP to 2.3.12. Consumers must use a compatible Kotlin compiler. This toolchain requirement needs release notes and a new version; do not overwrite 0.2.1.
- Budget exhaustion now fails explicitly. Cyclic shape structures and invalid embedding dimensions/norms are rejected. These intentional stricter behaviors are documented in [release acceptance](release-acceptance.md).

## ABI reporter migration

The conservative comparison of 24 pre-hardening Kotlin 2.3 dumps against Kotlin 2.4 dumps reports eight missing entries and one changed class header. Direct `javap -p -s` inspection confirms that all eight corresponding JVM members still exist:

| Class | Entry omitted by the newer report |
|---|---|
| `GenerationException` | Synthetic default constructor |
| `RdfException` | Synthetic default constructor |
| `RdfFormatException` | Synthetic default constructor |
| `JenaRepository` | Synthetic constructor bridge |
| `OnnxEmbeddingModel` | Synthetic constructor bridge |
| `QualityChecker` | Synthetic constructor bridge |
| `DefaultQualityExplanationEnricher` | `SYSTEM_PROMPT` field |
| `DefaultQualityExplanationEnricher` | `FIX_JSON_PREFIX` field |

`SemanticEnricher` now implements `AutoCloseable`; the conservative comparator flags its changed class header. Direct before/after JVM descriptor comparison found no removed public or protected members. The new `close()` is idempotent and closes only the model created by `default()`; constructor-supplied models remain caller-owned. Evidence: `build/review/semantic-enricher-bytecode-comparison.json`.

The comparator deliberately continues to flag these rather than introducing a blanket synthetic-member exception that could hide real binary breaks. Review evidence is retained locally in `build/review/readiness-api-comparison.json` and `api-bytecode-comparison.json`.

Five JVM-mangled Jena methods corresponding to Kotlin `internal` declarations changed their module suffix under the toolchain migration. These are not supported Kotlin public API. Java/reflection clients that bypass Kotlin visibility must be considered when reviewing the actual previous public artifact.

## Local consumer evidence

The preserved pre-hardening `SmokeKt.class` executed successfully against the final staged BOM and both providers with Kotlin/Java compilation disabled. Its SHA-256 remained `4145780da74148d4207fcb0f5f3f3283a44935cc5be24ddabf69630e24efdfe1`. A fresh Kotlin 2.4.20 compilation and execution also passed. This checks a representative local client, not every API or an unidentified public release. Logs: `build/review/final-binary-consumer.log`, `final-source-consumer.log` and `staging-verification.json`.

## First public release confirmed — 13 September 2026

The maintainer confirmed that Kastor has not been published for external users. Comparison with a previous public binary is therefore not applicable to this first release. The local ABI and preserved-client checks above remain useful regression evidence; they are not presented as public-release compatibility evidence. Preserve the first published artifact checksums and toolchain as the baseline for subsequent releases. The production version and publication configuration still need to be chosen.
