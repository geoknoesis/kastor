# Reasoning (`rdf/reasoning/`)

| Directory | Gradle project | Role |
|-----------|----------------|------|
| [`facade/`](facade/) | `:rdf:reasoning` | Portable reasoning API, registry, memory provider |
| [`hermit/`](hermit/) | `:rdf:reasoning-hermit` | HermiT-backed OWL 2 DL integration |

Engine-specific providers ship under [`rdf/providers/`](../providers/README.md). See [`settings.gradle.kts`](../../settings.gradle.kts) for `projectDir` mappings.
