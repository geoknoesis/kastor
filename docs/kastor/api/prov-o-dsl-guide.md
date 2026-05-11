# PROV-O DSL Guide

{% include version-banner.md %}

The PROV-O DSL adds readable helpers for [W3C PROV-O](https://www.w3.org/TR/prov-o/) assertions inside the same [TripleDsl](core-api.md) / `GraphDsl` blocks as the [Compact DSL Guide](compact-dsl-guide.md). It covers common generation, use, attribution, derivation, and time edges used in provenance graphs.

## Overview

- **What it is**: A `prov { }` block that emits standard `prov:` triples using `PROV` vocabulary IRIs from `com.geoknoesis.kastor.rdf.vocab.PROV`.
- **What it is not**: It does not expand qualified PROV patterns (`prov:QualifiedGeneration`, etc.) automatically; use `provValue` or the core triple DSL for those structures when you need them.
- **Prefixes**: `prov:` is built into `TripleDsl` and `GraphDsl` QName resolution. [Vocabularies](../concepts/vocabularies.md) registers `PROV` for `Vocabularies.findByPrefix("prov")`.

For SPARQL query building, `addCommonPrefixes("prov", …)` is also supported (see the [Kastor Query DSL Tutorial](../guides/kastor-query-dsl-tutorial.md)).

## Quick start

```kotlin
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.dsl.prov
import com.geoknoesis.kastor.rdf.vocab.PROV

fun main() {
    val dataset = Iri("http://example.org/dataset")
    val extraction = Iri("http://example.org/extraction")
    val rawFile = Iri("http://example.org/raw.ttl")
    val agent = Iri("http://example.org/agent")

    val repo = Rdf.memory()
    repo.add {
        prov {
            dataset wasGeneratedBy extraction
            extraction used rawFile
            dataset wasAttributedTo agent
            extraction.startedAtTime("2024-01-15T10:00:00Z")
            extraction.provLabel("Extract raw data", "en")
        }
        dataset `is` PROV.Entity
        extraction `is` PROV.Activity
    }
}
```

Use `` `is` `` for `rdf:type` (Kotlin keyword). **Time literals** use `xsd:dateTime` via `startedAtTime` / `endedAtTime` / `generatedAtTime` / `invalidatedAtTime` with an ISO-8601 lexical string.

## Infix helpers (resource–resource)

| Syntax | PROV property |
|--------|----------------|
| `entity wasGeneratedBy activity` | `wasGeneratedBy` |
| `activity used entity` | `used` |
| `activity generated entity` | `generated` |
| `activity invalidated entity` | `invalidated` |
| `entity wasInvalidatedBy activity` | `wasInvalidatedBy` |
| `activity wasAssociatedWith agent` | `wasAssociatedWith` |
| `entity wasAttributedTo agent` | `wasAttributedTo` |
| `delegate actedOnBehalfOf responsible` | `actedOnBehalfOf` |
| `entity wasDerivedFrom prior` | `wasDerivedFrom` |
| `entity wasRevisionOf prior` | `wasRevisionOf` |
| `entity wasQuotedFrom prior` | `wasQuotedFrom` |
| `entity hadPrimarySource source` | `hadPrimarySource` |
| `specific specializationOf general` | `specializationOf` |
| `a alternateOf b` | `alternateOf` |
| `collection hadMember member` | `hadMember` |
| `activity wasInformedBy prior` | `wasInformedBy` |
| `activity wasStartedBy trigger` | `wasStartedBy` |
| `activity wasEndedBy trigger` | `wasEndedBy` |
| `thing atLocation location` | `atLocation` |
| `activity hadPlan plan` | `hadPlan` |
| `influencer influenced influencee` | `influenced` |
| `thing wasInfluencedBy influencer` | `wasInfluencedBy` |

## Methods (literals and values)

| Call | PROV property |
|------|----------------|
| `resource.startedAtTime("…")` | `startedAtTime` |
| `resource.endedAtTime("…")` | `endedAtTime` |
| `resource.generatedAtTime("…")` | `generatedAtTime` |
| `resource.invalidatedAtTime("…")` | `invalidatedAtTime` |
| `resource.provLabel("text")` / `provLabel("text", "en")` | `label` |
| `resource.provValue(term)` | `value` |

## QNames

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.RDF

val g = Rdf.graph {
    val x = Iri("http://example.org/x")
    x - RDF.type - qname("prov:Entity")
}
```

## API entry points

- **`TripleDsl.prov(block)`** / **`GraphDsl.prov(block)`** — `com.geoknoesis.kastor.rdf.dsl`
- **`ProvTripleBuilder`** — infix relations and resource extension methods

## See also

- [Metadata & geometry DSLs](metadata-vocabulary-dsls.md) — DCAT, DCTerms, VoID, GeoSPARQL, OWL-Time
- [Compact DSL Guide](compact-dsl-guide.md) — General triple and graph DSL
- [SKOS DSL Guide](skos-dsl-guide.md) — Concept schemes
- [BFO DSL Guide](bfo-dsl-guide.md) — BFO / RO assertions
- [OWL DSL Guide](owl-dsl-guide.md) — OWL ontologies
- [PROV-O specification](https://www.w3.org/TR/prov-o/)
