# Backend providers (`rdf/providers/`)

Jena and RDF4J adapters and their optional reasoning bridges. **Gradle names** remain `:rdf:jena`, `:rdf:jena-reasoning`, `:rdf:rdf4j`, `:rdf:rdf4j-reasoning`. Kotlin packages stay **`com.geoknoesis.kastor.rdf.jena`** and **`…rdf.rdf4j`** (no `.providers` segment in package names).

| Directory | Gradle project |
|-----------|----------------|
| [`jena/`](jena/) | `:rdf:jena` |
| [`jena-reasoning/`](jena-reasoning/) | `:rdf:jena-reasoning` |
| [`rdf4j/`](rdf4j/) | `:rdf:rdf4j` |
| [`rdf4j-reasoning/`](rdf4j-reasoning/) | `:rdf:rdf4j-reasoning` |

See [`settings.gradle.kts`](../../settings.gradle.kts) for `projectDir` mappings.
