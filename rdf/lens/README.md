# Semantic Lens — Kastor portable execution profile

This experimental implementation compiles a bounded, typed subset of the
Semantic Lens algebra to SPARQL on real Kastor Jena and RDF4J providers.
It is **not** the complete Semantic Lens v0.23 engine or an endorsed W3C standard.

Supported operations include class/node selection, equality and EXISTS,
set intersection/union/difference, typed Boolean property filters, guarded
ego/network traversals (depth 0–3), and bounded property paths (sequence,
inverse, alternative). SHACL Core validates RDF-authored definitions; callers
should use `KastorRdfLensExecutor.executeValidated` with trusted shapes.

The node algebra now has a typed, provider-neutral logical optimizer and
`SparqlCompiler.explain()`, documenting applied rewrites and the exact SPARQL.
See [Optimization](docs/OPTIMIZATION.md) and the
[RDF execution profile](docs/RDF-PROFILE.md).

Typed Kotlin-only tabular views now provide projection, COUNT DISTINCT, facets
and multi-dimensional counts, executed in SPARQL on both providers. See
[Tabular lenses](docs/TABULAR-LENSES.md) for semantics and limitations.


```bash
./gradlew :rdf:lens:compileKotlin :rdf:lens:test --no-daemon --stacktrace
```

Execution uses streaming SELECT within read transactions and bounded results.
The caller must authorize dataset/graph access and enforce provider work,
memory and query-time limits. The optimizer is **not** yet cardinality-aware,
cost-based, or federated. The bridge returns bounded materialized sets; a
public streaming-results API is future work.
