# Semantic Lens typed tabular views (experimental)

This milestone adds a result-kind-aware typed algebra for **projected rows**,
**counts**, **facets**, and **multi-dimensional grouping** over the portable
node lens algebra. It is deliberately separate from node/network results:
tabular results are neither RDF nodes nor graph closure.

## Kotlin API

```kotlin
val companies = LensDsl.type("urn:ex:Company")
val columns = TabularDsl.project(
    companies,
    TabularLens.Column("industry", "urn:ex:industry"),
    TabularLens.Column("region", "urn:ex:region", required = true)
)
val distribution = TabularDsl.facet(companies, "urn:ex:industry", includeMissing = true)
val counts = TabularDsl.groupCount(
    companies,
    TabularLens.Column("industry", "urn:ex:industry"),
    TabularLens.Column("region", "urn:ex:region")
)
val total = TabularDsl.count(companies)

val totalRevenue = TabularDsl.sum(companies, "urn:ex:revenue")
val averageByIndustry = TabularDsl.avg(
    companies, "urn:ex:revenue",
    TabularLens.Column("industry", "urn:ex:industry")
)
val minimum = TabularDsl.min(companies, "urn:ex:revenue")
val maximum = TabularDsl.max(companies, "urn:ex:revenue")
// Custom result alias and operation are also available:
// TabularDsl.aggregate(companies, "urn:ex:revenue", NumericOperation.SUM, "revenue")

val executor = KastorTabularLensExecutor(repository)
val plan = executor.explain(distribution)
val result = executor.execute(distribution, KastorTabularLensExecutor.Limits(maxRows = 1000))
```

All tabular forms are **compiled into native SPARQL SELECT**, with
`SELECT DISTINCT ?node` as the inner selected universe. Predicate IRIs and
projection aliases are strictly validated; no free-form SPARQL is accepted.

## Semantics

- `Projection`: one result row per distinct combination of `?node` and
  selected property values; multivalued properties may produce multiple rows.
  Optional columns are unbound where a property is missing.
- `Count`: `COUNT(DISTINCT ?node)`, including 0 on an empty universe.
- `Facet`: distinct-node count per RDF term (including language/datatype).
  `includeMissing=true` includes an **unbound** facet bucket; false omits it.
  A node with several distinct facet values may count in several buckets,
  so the sum of counts may exceed the number of selected nodes.
- `GroupCount`: counts distinct nodes per dimension tuple. An optional
  dimension can be unbound, and multivalued dimensions produce multiple
  combinations; counts therefore should not be added to obtain a universe
  count without deduplication.
- `NumericAggregate`: `SUM`, `AVG`, `MIN`, `MAX` over a numeric
  predicate, optionally grouped by 1–3 dimensions. It first selects distinct
  **(node, dimension tuple, RDF numeric term)** combinations, so different
  nodes with the same amount still contribute independently. Multiple distinct
  numeric values on one node each contribute once per dimension tuple.
  Numeric values of type xsd:integer, xsd:decimal, xsd:float, and xsd:double
  are accepted using SPARQL `isNumeric`. Non-numeric and missing values are
  excluded from the measure but keep their nodes in dimension groups.
  Missing dimensions are represented as unbound buckets unless required.
  On an empty input, `SUM` returns zero; `AVG`, `MIN`, and `MAX` have an
  unbound result. Groups with no numeric measure follow the same rule.
  The compiled SPARQL includes a private numeric COUNT column; the Kastor
  tabular executor uses it to normalize provider-specific empty-aggregate
  results. Callers executing the raw SPARQL independently must apply the same
  empty-group normalization if they need this portable contract.
  Each operation is a separate single-measure lens to avoid Cartesian
  multiplication of independent measure paths.
- No application-side joins or aggregate computations. SPARQL provider
  semantics (collation, numeric errors, entailment, dataset scope) apply.

## Safety and limits

- 1–8 projection columns, 1–3 grouping dimensions, unique safe aliases.
- Numeric aggregates use only fixed functions and validated property IRIs;
  arbitrary SPARQL is never accepted.
- Generated SPARQL limited to 100,000 characters by default.
- Bounded streaming via `withSelectRows` in one read transaction.
  The public `Table` API materializes at most `maxRows` after streaming.
  Overflow raises an exception, never silently truncates a facet.
- Caller must provide an **authorized** repository/graph scope and enforce
  provider query time/work/memory quotas; this library does not grant access.
- These Kotlin types are **not yet loaded from RDF ontology definitions**.
  The existing `executeValidated()` is for saved node/network RDF lenses;
  it does not validate tabular IR definitions, which are currently Kotlin-only.
  Calling code must not treat this as a complete SHACL execution profile.

## Follow-up increments

- Multiple independent measures in one tabular result with safe join strategies,
  plus configurable treatment of non-numeric values and data-quality diagnostics.
- Typed ontology/RDF representation and SHACL profile for tabular definitions.
- Dedicated streaming table cursor, pagination and continuation tokens.
- Provider statistics, physical plan alternatives, join-order benchmarks,
  QueryWeave federation, and visual query-builder support.
