# BFO DSL Guide

{% include version-banner.md %}

The BFO DSL adds a small, readable layer for **instance-level** triples that use [Basic Formal Ontology](http://purl.obolibrary.org/obo/bfo.owl) (BFO) classes and common [Relation Ontology](http://purl.obolibrary.org/obo/ro.owl) (RO) object properties. It lives alongside the general triple DSL (`repo.add { }`, `Rdf.graph { }`) the same way the [RDFS DSL Guide](rdfs-dsl-guide.md) is a focused helper for schema-style graphs.

## Overview

- **What it is**: A `bfo { }` block inside [TripleDsl](core-api.md) / `GraphDsl` that expands to ordinary `RdfTriple`s using official OBO PURL IRIs (`http://purl.obolibrary.org/obo/…`).
- **What it is not**: It does not define an OWL/RDFS ontology by itself. Use the [RDFS DSL Guide](rdfs-dsl-guide.md) or [OWL DSL Guide](owl-dsl-guide.md) for class axioms; use BFO helpers for **assertions** (parthood, participation, location, inherence).
- **Vocabularies**: `BFO` and `RO` in `com.geoknoesis.kastor.rdf.vocab` expose curated `Iri` constants; `OBO` is the shared namespace helper. The `obo:` prefix is built into the triple/graph DSL (see [Vocabularies](../concepts/vocabularies.md)).

### Benefits

- **Readable**: `cell partOf tissue` instead of repeating long predicate IRIs.
- **Type-safe**: Predicates resolve to `BFO.*` / `RO.*` at compile time.
- **Interoperable**: Same triples you would write by hand; any RDF store or reasoner sees standard OBO terms.

## Quick start

```kotlin
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.dsl.bfo
import com.geoknoesis.kastor.rdf.vocab.BFO

fun main() {
    val cell = Iri("http://example.org/cell")
    val tissue = Iri("http://example.org/tissue")
    val process = Iri("http://example.org/process")

    val repo = Rdf.memory()
    repo.add {
        bfo {
            cell partOf tissue
            tissue participatesIn process
            process hasParticipant cell
        }
        cell `is` BFO.materialEntity
    }
}
```

### Kotlin note: `rdf:type` alias

The triple DSL uses a backtick infix `` `is` `` for `rdf:type` (because `is` is a Kotlin keyword). Always write `` resource `is` BFO.materialEntity ``, not `resource is BFO.materialEntity`.

## Where you can use `bfo { }`

| API | Example |
|-----|---------|
| Default graph on a repository | `repo.add { bfo { … } }` |
| `Rdf.graph { }` | `Rdf.graph { bfo { … } }` |

Inside the block, subjects and objects are normal `RdfResource` values (`Iri`, blank nodes, etc.).

## Infix helpers (`BfoTripleBuilder`)

Each helper appends one triple to the current DSL triple list.

| Syntax | Predicate (OBO local name) | Typical reading |
|--------|---------------------------|-----------------|
| `a partOf b` | `BFO_0000050` | *a* is part of *b* |
| `a hasPart b` | `BFO_0000051` | *a* has part *b* |
| `a locatedIn b` | `RO_0001025` | *a* is located in *b* |
| `a participatesIn b` | `RO_0000056` | *a* participates in process *b* |
| `a hasParticipant b` | `RO_0000057` | process *a* has participant *b* |
| `a inheresIn b` | `RO_0000052` | dependent *a* inheres in bearer *b* |
| `a bearerOf b` | `RO_0000053` | *a* is bearer of *b* |

BFO **class** IRIs (for example `BFO.materialEntity`, `BFO.process`) are not added by `bfo { }`; assert them with the main DSL, e.g. `` thing `is` BFO.process ``, or with `thing - RDF.type - BFO.materialEntity`.

## QNames and `obo:`

Built-in `obo:` maps to `http://purl.obolibrary.org/obo/`. You can mix QNames with vocabulary objects:

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.BFO
import com.geoknoesis.kastor.rdf.vocab.RDF

val g = Rdf.graph {
    val x = Iri("http://example.org/x")
    x - RDF.type - qname("obo:BFO_0000040")  // material entity
}
```

## API entry points

- **`TripleDsl.bfo(block)`** / **`GraphDsl.bfo(block)`** — `com.geoknoesis.kastor.rdf.dsl`
- **`BfoTripleBuilder`** — holds the infix methods above

## See also

- [PROV-O DSL Guide](prov-o-dsl-guide.md) — W3C provenance in the triple DSL
- [RDFS DSL Guide](rdfs-dsl-guide.md) — RDF Schema vocabulary construction
- [SKOS DSL Guide](skos-dsl-guide.md) — SKOS thesauri and concept schemes
- [OWL DSL Guide](owl-dsl-guide.md) — OWL 2 ontology construction
- [Compact DSL Guide](compact-dsl-guide.md) — General triple and graph DSL
- [Vocabularies](../concepts/vocabularies.md) — Prefixes and vocabulary objects
