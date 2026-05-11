# SKOS DSL Guide

{% include version-banner.md %}

The SKOS DSL provides readable helpers for [Simple Knowledge Organization System](https://www.w3.org/TR/skos-reference/) (SKOS) **assertions** inside the same triple and graph DSL as the [RDFS DSL Guide](rdfs-dsl-guide.md) and [Compact DSL Guide](compact-dsl-guide.md). Use it to state broader–narrower links, scheme membership, labels, and documentation properties without spelling out every predicate IRI.

## Overview

- **What it is**: A `skos { }` block on [TripleDsl](core-api.md) / `GraphDsl` that appends `RdfTriple`s using [SKOS](https://www.w3.org/TR/skos-reference/) vocabulary constants from `com.geoknoesis.kastor.rdf.vocab.SKOS`.
- **What it is not**: It does not replace the full [RDFS DSL Guide](rdfs-dsl-guide.md) or [OWL DSL Guide](owl-dsl-guide.md) for formal class axioms. Assert `rdf:type skos:Concept` (or `` concept `is` SKOS.Concept ``) alongside `skos { }` as needed.
- **Prefixes**: The `skos:` prefix is built into `TripleDsl` and `GraphDsl` QName resolution (same mechanism as `rdfs:`, `obo:`, `prov:`, …). See [Vocabularies](../concepts/vocabularies.md).

### Benefits

- **Readable hierarchy**: `city broader country` and `country narrower city`.
- **Scheme wiring**: `hasTopConcept`, `topConceptOf`, `inScheme` as infix helpers.
- **Labels and notes**: `prefLabel`, `altLabel`, `definition`, … as methods with an optional language tag.

## Quick start

```kotlin
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.dsl.skos
import com.geoknoesis.kastor.rdf.vocab.SKOS

fun main() {
    val scheme = Iri("http://example.org/scheme")
    val country = Iri("http://example.org/country")
    val city = Iri("http://example.org/city")

    val repo = Rdf.memory()
    repo.add {
        skos {
            scheme hasTopConcept country
            country topConceptOf scheme
            country.prefLabel("Country", "en")
            city.prefLabel("City")
            city broader country
            country narrower city
            city inScheme scheme
        }
        country `is` SKOS.Concept
        scheme `is` SKOS.ConceptScheme
    }
}
```

Use the backtick form `` `is` `` for `rdf:type` (Kotlin reserves `is`).

**Literal helpers** (`prefLabel`, `altLabel`, `definition`, …) take an optional second parameter for the language tag; use **dot** notation so Kotlin does not treat them as invalid infix calls, for example `country.prefLabel("Country", "en")`.

## Infix helpers (resource–resource)

| Syntax | SKOS property | Typical use |
|--------|----------------|------------|
| `a broader b` | `skos:broader` | *a* is narrower than *b* |
| `a narrower b` | `skos:narrower` | *a* is wider than *b* |
| `a related b` | `skos:related` | Associative link |
| `a broaderTransitive b` | `skos:broaderTransitive` | Transitive broader |
| `a narrowerTransitive b` | `skos:narrowerTransitive` | Transitive narrower |
| `a broaderMatch b` | `skos:broaderMatch` | Mapping link |
| `a narrowerMatch b` | `skos:narrowerMatch` | Mapping link |
| `a relatedMatch b` | `skos:relatedMatch` | Mapping link |
| `a exactMatch b` | `skos:exactMatch` | Mapping link |
| `a closeMatch b` | `skos:closeMatch` | Mapping link |
| `a inScheme b` | `skos:inScheme` | Concept in scheme |
| `a hasTopConcept b` | `skos:hasTopConcept` | Scheme to top concept |
| `a topConceptOf b` | `skos:topConceptOf` | Top concept to scheme |
| `a member b` | `skos:member` | Collection member |

## Literal and documentation helpers

Called on the subject resource: `subject.prefLabel("text")` or `subject.prefLabel("text", "en")`.

- **Labels**: `prefLabel`, `altLabel`, `hiddenLabel`, `notation`
- **Documentation**: `definition`, `scopeNote`, `example`, `note`, `changeNote`, `editorialNote`, `historyNote`

Without a language tag, plain `xsd:string` literals are emitted; with a tag, language-tagged literals (`rdf:langString`) are used.

## QNames

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.RDF

val g = Rdf.graph {
    val x = Iri("http://example.org/x")
    x - RDF.type - qname("skos:Concept")
}
```

## API entry points

- **`TripleDsl.skos(block)`** / **`GraphDsl.skos(block)`** — `com.geoknoesis.kastor.rdf.dsl`
- **`SkosTripleBuilder`** — infix relations and resource extension methods for literals

## See also

- [PROV-O DSL Guide](prov-o-dsl-guide.md) — W3C provenance in the triple DSL
- [RDFS DSL Guide](rdfs-dsl-guide.md) — RDF Schema vocabulary construction
- [BFO DSL Guide](bfo-dsl-guide.md) — BFO / RO instance triples
- [OWL DSL Guide](owl-dsl-guide.md) — OWL 2 ontology construction
- [SHACL DSL Guide](shacl-dsl-guide.md) — Validation shapes
