# Semantic Lens: logical optimizer (first increment)

This module implements a **provider-neutral, semantics-preserving** optimization
pass for the portable typed Semantic Lens node algebra. It is deliberately not
yet a cost-based or federated optimizer.

## API

```kotlin
val lens = LensDsl.filter(
    LensDsl.filter(LensDsl.type("urn:ex:Company"), isHealthcare),
    isActive
)
val explained = SparqlCompiler().explain(lens)
println(explained.plan.rules)
println(explained.plan.nodesBefore)
println(explained.plan.nodesAfter)
println(explained.sparql)

val sparql = SparqlCompiler().select(lens) // same optimized SPARQL
```

`LensOptimizer().optimize(lens)` is also available without SPARQL serialization.
Its plan returns the rewritten lens tree, applied rule names, and logical lens
node counts. The node counts are **not** result cardinalities or cost estimates.

## Implemented safe rewrites

- Flatten nested intersections and unions and deduplicate identical operands.
- Flatten nested AND/OR predicates and deduplicate identical conditions.
- Eliminate double Boolean negation.
- Fuse consecutive node filters against the *same bound node* into one
  conjunction, preserving EXISTS-based, multivalued, and missing-path semantics.

Rewrites avoid distributing AND over OR, treating RDF absence as OWL negation,
introducing raw SPARQL, or changing named-graph and authorization scope.
Individual filter/path IRIs remain validated by the existing compiler.

## Provider integration and checks

Both `select()` and network lens `graphPattern()` pass through the optimizer.
Compilation remains provider-side SPARQL; this pass does not fetch intermediate
node sets to the application. The Jena and RDF4J provider lanes run dedicated
`OptimizedAlgebra` tests, covering compiled filters, set operations, double
negation, absent paths, and logical-plan inspection.

## Next increments (not implemented here)

1. Typed projection, facet, aggregation and edge algebra in a common IR.
2. Statistics and dataset-sensitive cardinality estimates from the providers.
3. Capability-aware join/aggregation pushdown and physical plan alternatives.
4. `EXPLAIN` / `PROFILE` with actual rows, execution timing and work limits.
5. Native Grit / QueryWeave adapters and federation boundaries.

No provider-specific join order or cost claim should be made until (2) and (3)
are verified with representative benchmarks.
