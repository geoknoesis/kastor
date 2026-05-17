# SPARQL stack (`rdf/sparql/`)

| Directory | Gradle project | Role |
|-----------|----------------|------|
| [`contract/`](contract/) | `:rdf:sparql-contract` | SPARQL query marker types (tiny API) |
| [`lang/`](lang/) | `:rdf:sparql-lang` | AST, renderer, `select {}` DSL |
| [`endpoint/`](endpoint/) | `:rdf:sparql` | Remote SPARQL endpoint / repository integration |

Maven artifact IDs are unchanged (`rdf-sparql-contract`, `sparql-lang`, `rdf-sparql`). Paths are wired in [`settings.gradle.kts`](../../settings.gradle.kts).
