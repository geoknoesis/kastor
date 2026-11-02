# Semantic Lens typed expressions and SHACL execution profile

Status: experimental extension of the unofficial [Semantic Lens Ontology v0.2](https://example.org/semantic-lens/1.0#). **The operator IRIs introduced here are provisional, not W3C terms.**

## What is supported

Node filters: `lens:FilterLens` with a single `lens:inputLens` and `lens:condition`. A condition is a typed `lens:ComparisonExpression` or a `lens:BooleanExpression` group. Path expressions use **one predicate** via `lens:BoundPathExpression/lens:pathPredicate`; constants use `lens:ConstantExpression/lens:constantIri` or `lens:literalValue` (exactly one).

Comparison operator resources: `lens:Equal`, `lens:NotEqual`, `lens:GreaterThan`, `lens:GreaterOrEqual`, `lens:LessThan`, `lens:LessOrEqual`. Boolean group operators: `lens:And`, `lens:Or`, `lens:Not`. Unrecognized operators fail at RDF loading.

## Formal evaluation semantics

`Compare(Path(p), op, Constant(c))` is **true** for entity `n` when *at least one* `(n,p,v)` triple satisfies `v op c` according to SPARQL expression evaluation. If `p` has no values, positive comparison is **false**. `NOT(Compare(...))` is then true if no matching value is found, including when the property is absent. A comparison of two paths is true if at least one pair of their values satisfies the operator.

`AND`, `OR`, and `NOT` operate over these Boolean predicates, using SPARQL `EXISTS` inside a server-side `FILTER`. They are not OWL entailment/negation. The comparison is scoped to the selected authorized graph/dataset; reasoning behavior is controlled by the provider context. Multivalued path semantics should not be confused with `ALL values` quantification.

**Limitations:** no multi-segment SHACL paths, variable binding across separate Boolean comparisons, full quantified relationships, arbitrary custom functions, unknown SPARQL expressions, or automatic ontology imports. A typed literal must carry an absolute datatype IRI. Operators and IRI operands are validated before serialization.

## RDF example

```turtle
@prefix lens: <https://example.org/semantic-lens/1.0#> .
@prefix ex: <urn:filter:> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

ex:companies a lens:ClassLens ; lens:selectClass ex:Company .
ex:age a lens:BoundPathExpression ; lens:pathPredicate ex:age .
ex:minAge a lens:ConstantExpression ; lens:literalValue "18"^^xsd:integer .
ex:adult a lens:ComparisonExpression ;
  lens:leftExpression ex:age ;
  lens:operator lens:GreaterThan ;
  lens:rightExpression ex:minAge .
ex:adults a lens:FilterLens ;
  lens:inputLens ex:companies ;
  lens:condition ex:adult .
```

## Kastor Kotlin API

```kotlin
val root = LensDsl.filter(
    LensDsl.type("urn:filter:Company"),
    Filters.compare(
        Filters.path("urn:filter:age"),
        CompareOperator.GT,
        Filters.constant(Value.TypedLiteral(
            "18", "http://www.w3.org/2001/XMLSchema#integer"
        ))
    )
)
val sparql = SparqlCompiler().select(root)
```

Or load the RDF above and validate **before compilation**:

```kotlin
val definitions = Rdf.parse(definitionText, "TURTLE")
val shapes = Rdf.parse(
    checkNotNull(javaClass.getResource("/lens-execution-shapes.ttl")).readText(),
    "TURTLE"
)
val result = KastorRdfLensExecutor(repository, definitions)
    .executeValidated(Iri("urn:filter:adults"), shapes)
```

The default `execute()` API is preserved for legacy source compatibility and does **not** automatically perform SHACL validation. **Production callers should use `executeValidated()` with trusted SHACL shapes**. Structural validation by `LensRdfLoader` still occurs for either method; it is not a substitute for SHACL.

`LensDefinitionShaclValidation` runs Kastor's **native** SHACL validator with strict mode and rejects reports where `sh:conforms` is false or the violation list was truncated. It validates the *whole supplied definition graph*, not just one selected lens. It does not resolve remote `owl:imports`. The bundled execution-profile shapes supplement, and do not replace, the complete standalone v0.2 ontology shapes.

## Resource and security boundaries

Expressions are compiled into SPARQL inside Kastor's Jena/RDF4J providers. The application must enforce graph permissions, resource limits and a trusted query/definition policy. Native SHACL validation checks structural data quality, **not authorization**, executable cost, or arbitrary code safety. When accepting untrusted RDF definitions, apply size/shape-depth and timeout limits before and during parsing, and do not fetch external RDF imports.

## Conformance

`TypedFilterShaclProviderTest` runs against both Jena and RDF4J. It checks validated RDF execution, DSL equivalence, existential comparison, missing/multivalued properties, unknown operators, and SHACL shape failure.
