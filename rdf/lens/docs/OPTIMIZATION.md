# Semantic Lens: logical optimizer

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

The optimizer visits each lens node once, bottom-up, and returns a canonical
form: a core (class selector, union, or flat intersection) wrapped by the
restrictions that test the selected node, ordered Equals, Exists, Difference,
then at most one Filter.

- Flatten nested intersections and unions and deduplicate identical operands.
- Flatten nested AND/OR predicates and deduplicate identical conditions.
- Eliminate double Boolean negation.
- Fuse all node filters against the *same bound node* into one conjunction,
  preserving EXISTS-based, multivalued, and missing-path semantics.
- Pull restrictions out of intersections: `and(filter(A, c), B)` becomes
  `filter(and(A, B), c)`, likewise for Equals, Exists and Difference. Composed
  lenses that share a base then scan that base once.
- Merge union operands over a shared input: `or(filter(A, c1), filter(A, c2))`
  becomes `filter(A, any(c1, c2))`, and `or(A, filter(A, c))` becomes `A`.
- Rewrite `minus(L, R)` as a negated filter when R is L's core under
  restrictions L already satisfies plus extra filter clauses. Skipped when an
  extra clause compares two constants, because that can raise a SPARQL error
  and `!(error)` differs from `NOT EXISTS`.

Rewrites avoid distributing AND over OR, treating RDF absence as OWL negation,
introducing raw SPARQL, or changing named-graph and authorization scope.
Individual filter/path IRIs remain validated by the existing compiler.

## Generated SPARQL

The query text is shaped so that a provider's own planner starts from the most
selective part. Each item below was measured on Jena and RDF4J; see the
[user guide](USER-GUIDE.md#measured-effect) for numbers.

- An intersection compiles to one flat group, so the provider can plan all of
  its triple patterns as a single basic graph pattern.
- `Exists` tests its target in a nested `EXISTS`, and a sequence path in a
  filter becomes one nested `EXISTS` per step. Each level holds one pattern
  whose start is already bound. RDF4J plans an `EXISTS` body without the outer
  bindings; with several patterns in one body it could start from the whole
  target class, once per candidate node.
- Network walks are flat: hops are plain triple patterns in the walk's group,
  and the result variables (`?node`, `?subject`, `?object`) are used directly
  as walk variables instead of being copied with `BIND`. A hop wrapped in its
  own group with a `BIND` was evaluated independently of the seeds on RDF4J.
- A node guard without a union is emitted inline in the walk. It yields at most
  one solution per node and binds nothing else, so it cannot multiply rows. A
  guard containing a union stays in `FILTER EXISTS`.
- TRAVERSED network edges use one branch per walk length (two for `BOTH`, one
  per orientation), emitting the final hop; a hop followed by further hops is
  already witnessed by the shorter walk.
- INDUCED network edges join the reachable node set (two DISTINCT subqueries)
  to the edge pattern instead of re-walking the network per candidate edge.
- `KastorRdfLensExecutor` and `KastorTabularLensExecutor` append `LIMIT max+1`,
  so the provider can stop early; overflow still fails closed.
- `KastorRdfLensExecutor.prepare` / `prepareValidated` load, validate and
  compile a lens once for repeated execution. The result is a snapshot of the
  definitions graph.

`LensQueryBenchmark` is an opt-in wall-clock benchmark over both in-memory
providers (`LENS_BENCHMARK=1`). Run it before and after any change to the
generated query text: several of the shapes above were found by measuring, and
a shape that helps one provider can hurt the other.

## Provider integration and checks

Both `select()` and network lens `graphPattern()` pass through the optimizer.
Compilation remains provider-side SPARQL; this pass does not fetch intermediate
node sets to the application. The Jena and RDF4J provider lanes run dedicated
`OptimizedAlgebra` tests. `LensCompositionOptimizationProviderTest` checks
several hundred randomly composed lenses, and guarded networks at every depth
and closure mode, against independent in-memory evaluators on both providers.

## Next increments (not implemented here)

1. Typed projection, facet, aggregation and edge algebra in a common IR.
2. Statistics and dataset-sensitive cardinality estimates from the providers.
3. Capability-aware join/aggregation pushdown and physical plan alternatives.
4. `EXPLAIN` / `PROFILE` with actual rows, execution timing and work limits.
5. Native Grit / QueryWeave adapters and federation boundaries.

No provider-specific join order or cost claim should be made until (2) and (3)
are verified with representative benchmarks.
