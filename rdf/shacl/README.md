# SHACL (`rdf/shacl/`)

Physical grouping for SHACL-related Gradle modules. **Project IDs and Maven coordinates are unchanged** (see each submodule).

| Directory | Gradle project | Published artifact (group `com.geoknoesis.kastor`) |
|-----------|----------------|-----------------------------------------------------|
| [`validation/`](validation/README.md) | `:rdf:shacl-validation` | `shacl-validation` |
| [`dsl/`](dsl/build.gradle.kts) | `:rdf:shacl-dsl` | `rdf-shacl-dsl` |

`settings.gradle.kts` maps each included project to its directory via `project(...).projectDir = file("rdf/shacl/...")` so existing `project(":rdf:shacl-validation")` / `project(":rdf:shacl-dsl")` references stay valid.

**Dependency rule:** validation must **not** depend on the DSL module; the DSL builds shapes/constraints on the SPARQL stack only.

**Sibling directories under `rdf/`:** portable [`core/`](../core), SPARQL stack [`sparql/`](../sparql), backend adapters [`providers/`](../providers), reasoning [`reasoning/`](../reasoning).
