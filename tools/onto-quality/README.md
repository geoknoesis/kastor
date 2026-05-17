# Ontology quality suite (`tools/onto-quality/`)

Gradle projects **`:tools:onto-quality`** (library), **`:tools:onto-quality-cli`**, **`:tools:onto-quality-metrics`**, **`:tools:onto-quality-embed`**, **`:tools:onto-quality-llm-koog`** are grouped here on disk. Maven coordinates and `project(":tools:…")` references are unchanged; see root `settings.gradle.kts` for `projectDir` mappings.

| Directory | Gradle project |
|-----------|----------------|
| [`library/`](library/README.md) | `:tools:onto-quality` — catalogues, `QualityChecker`, bundled SHACL |
| [`cli/`](cli/build.gradle.kts) | `:tools:onto-quality-cli` |
| [`metrics/`](metrics/README.md) | `:tools:onto-quality-metrics` |
| [`embed/`](embed/build.gradle.kts) | `:tools:onto-quality-embed` |
| [`llm-koog/`](llm-koog/build.gradle.kts) | `:tools:onto-quality-llm-koog` |

Primary contributor docs (calibration, pitfall triage) live with the **library** module under [`library/`](library/).
