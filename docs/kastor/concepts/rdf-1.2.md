# RDF 1.2 in Kastor

Kastor 0.2.0 implements the [W3C RDF 1.2](https://www.w3.org/TR/rdf12-concepts/)
data model end-to-end. This page summarises what RDF 1.2 changes versus the
previous RDF-star based model and shows the corresponding Kastor APIs. If you
are upgrading from 0.1.x, also read the
[migration guide](../guides/migrating-to-rdf-1.2.md).

## What's new

### Triple terms

A *triple term* is a kind of RDF term that names a triple without asserting
it. Triple terms appear only as the **object** of another triple, and their
RDF 1.2 lexical form uses parenthesised angle brackets:

```
<<( :alice :age 30 )>>
```

In Kastor, triple terms are represented by [`TripleTerm`](../api/api-reference.md)
which extends `RdfTerm` (not `RdfResource`); the type system therefore prevents
you from using a triple term in subject position.

```kotlin
val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
val tt: RdfTerm = TripleTerm(claim)            // <<( :alice :age 30 )>>
```

### `rdf:reifies`

Adding metadata to a triple uses the new `rdf:reifies` property: an IRI or
blank node *reifier* is linked to the triple term, and metadata triples hang
off the reifier.

```kotlin
val reifier = bnode("r1")
val triples = listOf(
    claim,                                                      // assert it
    RdfTriple(reifier, RDF.reifies, TripleTerm(claim)),         // name it
    RdfTriple(reifier, ex("certainty"), 0.9.toLiteral()),       // annotate it
)
```

The Kastor DSL exposes a `reifies { ... }` builder that does the bnode
plumbing for you:

```kotlin
val graph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
    triple(claim.subject, claim.predicate, claim.obj)
    reifies(claim) { reifier ->
        reifier - iri("http://example.org/certainty") - 0.9
    }
}
```

### Directional language strings (`rdf:dirLangString`)

Language-tagged strings now optionally carry a base direction (`ltr` / `rtl`)
that round-trips correctly through serialisation. The new datatype
`rdf:dirLangString` distinguishes directional literals from plain
`rdf:langString`.

```kotlin
val rtl = LangString("\u0645\u0631\u062D\u0628\u0627", "ar", Direction.RTL)
println(rtl)              // "..."@ar--rtl
println(rtl.datatype)     // <http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString>

// DSL helper
val ltr = lang("Hello", "en", Direction.LTR)
```

### Updated `RDF` vocabulary

The `RDF` vocab object exposes new constants:

- `RDF.reifies`, `RDF.reifier`, `RDF.TripleTerm` (RDF 1.2 reification)
- `RDF.dirLangString` (RDF 1.2 datatype)
- `RDF.HTML`, `RDF.JSON`, `RDF.CompoundLiteral`

The legacy reification vocabulary (`rdf:Statement`, `rdf:subject`,
`rdf:predicate`, `rdf:object`) is still present for backwards compatibility but
is marked `@Deprecated`.

### SPARQL 1.2 alignment

The SPARQL renderer now emits the RDF 1.2 triple-term syntax
(`<<( s p o )>>`) and directional language literals (`"text"@lang--ltr`).
The SPARQL 1.2 built-ins `LANGDIR`, `STRLANGDIR`, `TRIPLE`, `SUBJECT`,
`PREDICATE`, `OBJECT`, `isTRIPLE` are registered in the extension function
registry.

The new AST nodes are `TripleTermPatternAst`, `TripleTermObjectPatternAst`,
and `ReifierPatternAst`. The legacy `QuotedTriplePatternAst` and
`RdfStarTriplePatternAst` remain available but are `@Deprecated` and emit the
RDF 1.2 syntax during rendering for compatibility.

## Provider capabilities

```kotlin
val provider = JenaProvider()
provider.getCapabilities("memory").rdfVersion          // "1.2"
provider.getCapabilities("memory").supportsTripleTerms // true

val mem = MemoryRepositoryProvider()
mem.getCapabilities("memory").rdfVersion               // "1.1"
mem.getCapabilities("memory").supportsTripleTerms      // false
```

The bundled in-memory provider remains an RDF 1.1 graph store; for full RDF 1.2
serialization, parsing, and SPARQL, use `:rdf:jena` or `:rdf:rdf4j`. RDF4J
versions older than 5.2 may surface RDF 1.2 features through the legacy
RDF-star APIs - the `Rdf4jTerms` bridge handles the fallback automatically.

## See also

- [Migration guide for 0.1.x to 0.2.0](../guides/migrating-to-rdf-1.2.md)
- [SPARQL 1.2 features](../features/sparql-1.2.md)
- [W3C RDF 1.2 Concepts](https://www.w3.org/TR/rdf12-concepts/)
- [W3C RDF 1.2 Turtle](https://www.w3.org/TR/rdf12-turtle/)
