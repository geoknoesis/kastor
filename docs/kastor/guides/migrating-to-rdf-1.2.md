# Migrating from Kastor 0.1.x to 0.2.0 (RDF 1.2)

{% include version-banner.md %}

> **Documentation mode: How-to guide (upgrade).** **Explanation:** RDF 1.2 data model in Kastor → [RDF 1.2 in Kastor](../concepts/rdf-1.2.md), [RDF 1.2 conformance](../concepts/rdf-1.2-conformance.md). **Reference:** [CHANGELOG](../../../CHANGELOG.md).

## Problem

- Upgrade a codebase from **Kastor 0.1.x** to **0.2.0** without surprises around **triple terms**, **language strings**, **serialized SPARQL/RDF surface syntax**, and **provider capabilities**.

## Prerequisites

- Builds targeting **0.2.0** artifacts (see [Installation](../getting-started/installation.md)).
- Awareness of any **RDF-star / quoted triple** subjects or snapshot tests that pin **`<< … >>`** spelling.

## Steps

### Step 1: Audit triple-term subjects

In RDF 1.2 a triple term is *not* an **`RdfResource`** and cannot appear as the subject of a triple. The type system enforces this in **0.2.0**.

**Before (0.1.x, RDF-star)**

```kotlin
val annotated = RdfTriple(
    quoted(claim),                        // TripleTerm as subject
    Iri("http://example.org/source"),
    string("wikipedia"),
)
```

**After (0.2.0, RDF 1.2)**

```kotlin
val reifier = bnode("r1")
val annotated = listOf(
    RdfTriple(reifier, RDF.reifies, TripleTerm(claim)),
    RdfTriple(reifier, ex("source"), string("wikipedia")),
)
```

Or with the graph DSL (assuming **`graph`** is a **`MutableRdfGraph`** you are editing):

```kotlin
graph.add {
    reifies(claim) { reifier ->
        reifier - ex("source") - "wikipedia"
    }
}
```

### Step 2: Replace deprecated reification vocabulary in new code

`rdf:Statement`, `rdf:subject`, `rdf:predicate`, and `rdf:object` remain on the **`RDF`** vocab but are **`@Deprecated`**. Prefer **`RDF.reifies`** plus an explicit reifier node (see Step 1).

### Step 3: Account for `LangString` direction

The two-argument constructor remains, but **`LangString`** values with different **`direction`** are unequal even when lexical form and language tag match.

```kotlin
val a = LangString("Hello", "en")                // 0.1.x compatible
val b = LangString("Hello", "en", Direction.LTR) // RDF 1.2 directional

a == b  // false (datatype / internal representation differs)
```

If you intentionally ignore direction, compare **`lexical`** / **`lang`** explicitly or normalize construction on both sides.

### Step 4: Refresh snapshot expectations for triple-term syntax

Serialization, **`toString()`**, and the SPARQL renderer emit RDF **1.2** **`<<( s p o )>>`** groups instead of legacy **`<<s p o>>`**:

```kotlin
// Before:
assertTrue(out.contains("<< ?s ?p ?o >>"))
// After:
assertTrue(out.contains("<<( ?s ?p ?o )>>"))
```

Jena and RDF4J parsers still accept much of the legacy RDF-star surface syntax; existing **data files** generally keep loading.

### Step 5: Migrate SPARQL AST usage

Prefer **`TripleTermPatternAst`** and **`ReifierPatternAst`** over deprecated **`QuotedTriplePatternAst`** / **`RdfStarTriplePatternAst`** aliases.

```kotlin
// Before
where {
    quotedTriple(`var`("s"), `var`("p"), `var`("o"))
}

// After (new names; deprecated aliases still compile in 0.2.x)
import com.geoknoesis.kastor.rdf.sparql.TripleTermPatternAst
import com.geoknoesis.kastor.rdf.sparql.ReifierPatternAst
```

Deprecated AST symbols are scheduled for removal in **0.3.0**.

### Step 6: Extend capability handling

```kotlin
data class ProviderCapabilities(
    val rdfVersion: String = "1.1",
    val supportsTripleTerms: Boolean = false,
    // … existing fields …
)
```

Call sites that construct or **`copy`** **`ProviderCapabilities`** may need to supply or accept the new fields.

### Step 7: Plan persistence for legacy RDF-star subject terms

Repositories that stored **quoted triples as subjects** (`<<s p o>> p2 o2`) still parse from disk in many setups, but **Kastor 0.2.0** cannot surface impossible triple-term subjects through the typed API; backends may map them to **`BlankNode`** or drop them.

If you rely on subject-position triple terms:

1. Migrate data to **`rdf:reifies`** with a SPARQL **`INSERT … WHERE`** promotion (see **`rdf/providers/jena`** test fixtures for examples), or  
2. Stay on **0.1.x** until data is migrated.

### Step 8: RDF4J bridge versions

RDF4J **5.1.x** predates some **`createTripleTerm`** APIs. Kastor falls back to legacy **`createTriple`** where needed so triple terms continue to move across the bridge, but wire formats may remain RDF-star–skewed until you upgrade **RDF4J ≥ 5.2.x** when available.

## Validation

Work through this checklist after the code compiles:

- [ ] No **`TripleTerm`** (or deprecated quoted triple) remains in **subject** position.
- [ ] **`LangString`** equality expectations match direction semantics.
- [ ] Turtle / SPARQL snapshots accept **`<<( … )>>`** spelling.
- [ ] AST imports avoid deprecated **`QuotedTriplePatternAst`** / **`RdfStarTriplePatternAst`** for new code.
- [ ] **`ProviderCapabilities`** constructors and **`copy`** call sites compile with the new fields.
- [ ] Persistent stores with RDF-star subjects either migrated or consciously pinned to **0.1.x**.

## Troubleshooting

- **Tests fail only on string snapshots:** diff Turtle / SPARQL text for **`<<`** spacing before asserting semantic regressions.
- **“Lost” quoted triple subjects:** indicates unmigrated legacy layout—see Step 7.

## Related

- [RDF 1.2 in Kastor](../concepts/rdf-1.2.md)
- [Changelog](../../../CHANGELOG.md)
