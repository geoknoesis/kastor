# Migrating from Kastor 0.1.x to 0.2.0 (RDF 1.2)

Kastor 0.2.0 adopts the W3C RDF 1.2 data model. This guide walks through the
breaking changes you are most likely to hit and shows the recommended fix for
each. For a conceptual overview, see [RDF 1.2 in Kastor](../concepts/rdf-1.2.md).

## Quick checklist

- [ ] You no longer have any code that puts a `TripleTerm` in subject position.
- [ ] Code that compared `LangString` instances by structural equality still
      works, but be aware that two language strings with different `direction`
      values are now unequal.
- [ ] If you serialise to Turtle and inspect the output, expect the new
      `<<( s p o )>>` syntax instead of the legacy `<<s p o>>`.
- [ ] Any custom code that builds `QuotedTriplePatternAst` /
      `RdfStarTriplePatternAst` still compiles but emits a deprecation warning.
- [ ] If you query provider capabilities, the new fields `rdfVersion` and
      `supportsTripleTerms` are available.

## 1. `TripleTerm` is no longer an `RdfResource`

In RDF 1.2 a triple term is *not* a resource: it can never be the subject of a
triple. The type system enforces this in 0.2.0.

### Before (0.1.x, RDF-star)

```kotlin
val annotated = RdfTriple(
    quoted(claim),                        // TripleTerm as subject
    Iri("http://example.org/source"),
    string("wikipedia"),
)
```

### After (0.2.0, RDF 1.2)

```kotlin
val reifier = bnode("r1")
val annotated = listOf(
    RdfTriple(reifier, RDF.reifies, TripleTerm(claim)),    // name it
    RdfTriple(reifier, ex("source"), string("wikipedia")), // annotate it
)
```

Or with the DSL:

```kotlin
graph.add {
    reifies(claim) { reifier ->
        reifier - ex("source") - "wikipedia"
    }
}
```

## 2. Old reification vocabulary deprecated

`rdf:Statement`, `rdf:subject`, `rdf:predicate`, `rdf:object` still exist on
the `RDF` vocab but are `@Deprecated`. New code should use `RDF.reifies` plus
a triple term.

## 3. `LangString` carries a base direction

The two-argument constructor still works, so most existing code keeps
compiling unchanged. But two `LangString` values with the same lexical and
language but different directions are *not* equal.

```kotlin
val a = LangString("Hello", "en")                       // 0.1.x compatible
val b = LangString("Hello", "en", Direction.LTR)        // RDF 1.2 directional

a == b                                                  // false (different datatype)
```

If you have code that compares language strings ignoring direction, switch to
explicit field-level checks (`x.lexical == y.lexical && x.lang == y.lang`) or
ignore direction explicitly when constructing both sides.

## 4. New triple-term syntax in serialised output

Serialisation, `toString()`, and the SPARQL renderer all use the RDF 1.2
syntax `<<( s p o )>>`. Snapshot tests that asserted the legacy `<<s p o>>`
spelling need updating:

```kotlin
// Before:
assertTrue(out.contains("<< ?s ?p ?o >>"))
// After:
assertTrue(out.contains("<<( ?s ?p ?o )>>"))
```

Both Jena's and RDF4J's parsers continue to accept the legacy RDF-star syntax,
so existing data files keep loading.

## 5. SPARQL AST: prefer `TripleTermPatternAst` and `ReifierPatternAst`

```kotlin
// Before
where {
    quotedTriple(`var`("s"), `var`("p"), `var`("o"))
}

// After (still compiles via the deprecated alias, but new code should use):
import com.geoknoesis.kastor.rdf.sparql.TripleTermPatternAst
import com.geoknoesis.kastor.rdf.sparql.ReifierPatternAst
```

`QuotedTriplePatternAst` and `RdfStarTriplePatternAst` remain available
through 0.2.x and emit RDF 1.2 syntax. They are scheduled for removal in 0.3.0.

## 6. Provider capabilities have new fields

```kotlin
data class ProviderCapabilities(
    val rdfVersion: String = "1.1",
    val supportsTripleTerms: Boolean = false,
    // ... existing fields
)
```

Existing call-sites that don't reference these fields stay source-compatible.
If you destructure or copy `ProviderCapabilities`, regenerate the call.

## 7. Old TDB2 / NativeStore data

Repositories that hold *RDF-star* triples whose subject was a quoted triple
(legacy `<<s p o>> p2 o2`) keep loading - both Jena and RDF4J ship parsers that
accept the old syntax. However, when those statements are surfaced through
Kastor's API you will see them only via the object-position pathway: a
`TripleTerm` in subject position cannot be expressed in the new type system
and is reported as a `BlankNode` (Jena) or filtered out (RDF4J).

If you depend on subject-position triple terms, you have two options:

1. Migrate the data to the `rdf:reifies` pattern with a SPARQL `INSERT { ... }
   WHERE { ... }` query that promotes each triple-term subject to a reifier
   (sample query in the test fixtures under `rdf/jena/src/test/...`).
2. Stay on Kastor 0.1.x for those datasets.

## 8. RDF4J version note

RDF4J 5.1.x predates the `createTripleTerm` rename. Kastor's RDF4J bridge
falls back to the legacy `createTriple` method on these versions, so triple
terms still serialise and parse but use the older RDF-star wire format. Bump
to RDF4J 5.2.x or later (when available) to emit pure RDF 1.2.

## See also

- [RDF 1.2 in Kastor](../concepts/rdf-1.2.md)
- [Changelog](../../../CHANGELOG.md)
