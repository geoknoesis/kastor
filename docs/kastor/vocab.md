## Vocab helpers

`rdf-core` exposes common vocabularies as objects yielding `Iri` constants:

- `RDF` (`http://www.w3.org/1999/02/22-rdf-syntax-ns#`)
- `RDFS` (`http://www.w3.org/2000/01/rdf-schema#`)
- `OWL` (`http://www.w3.org/2002/07/owl#`)
- `SKOS` (`http://www.w3.org/2004/02/skos/core#`)
- `SHACL` (`http://www.w3.org/ns/shacl#`)
- `XSD` (`http://www.w3.org/2001/XMLSchema#`)

Example:
```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDFS

val label = RDFS.label // Iri("http://www.w3.org/2000/01/rdf-schema#label")
```

