# Semantic Lens user guide

A **lens** is a typed, reusable description of *which things you want out of an
RDF graph*. You build it in Kotlin (or load it from RDF), and Kastor compiles it
to SPARQL that runs inside your Jena or RDF4J repository. You never write or
concatenate SPARQL yourself.

This module is experimental. It implements a bounded subset of the Semantic
Lens algebra; see [Limitations](#limitations).

- [The three kinds of lens](#the-three-kinds-of-lens)
- [Setup](#setup)
- [Selecting nodes](#selecting-nodes)
- [Composing lenses](#composing-lenses)
- [Filter conditions](#filter-conditions)
- [Running a node lens](#running-a-node-lens)
- [Network lenses](#network-lenses)
- [Tabular lenses](#tabular-lenses)
- [Saved lens definitions in RDF](#saved-lens-definitions-in-rdf)
- [Limits and failures](#limits-and-failures)
- [Performance guide](#performance-guide)
- [Security](#security)
- [Limitations](#limitations)

## The three kinds of lens

| Kind | Answers | Result |
| --- | --- | --- |
| Node lens (`Lens`) | Which resources match? | a set of IRIs |
| Network lens (`NetworkLens`) | What is connected to these resources, within a few hops? | nodes and edges |
| Tabular lens (`TabularLens`) | What are the values, counts or totals for these resources? | rows and columns |

Network and tabular lenses are built on top of node lenses, so everything in
[Selecting nodes](#selecting-nodes) applies to all three.

## Setup

Inside this build, depend on the lens module and on a provider:

```kotlin
dependencies {
    implementation(project(":rdf:lens"))
    implementation(project(":rdf:jena"))   // or ":rdf:rdf4j"
}
```

The builders live in `org.geoknoesis.lens`; the executors that talk to a
repository live in `com.geoknoesis.kastor.rdf.lens`.

## Selecting nodes

```kotlin
import org.geoknoesis.lens.*

val usCompanies = lens {
    type("urn:ex:Company")
    where {
        sequence("urn:ex:locatedIn", "urn:ex:regionName") eq "US"
        not { path("urn:ex:age") lt 18 }
    }
    exclude { type("urn:ex:Restricted") }
}
```

Inside a `lens { }` block:

| Statement | Meaning |
| --- | --- |
| `type(iri)` | instances of a class. Several selectors in one block are intersected. |
| `has(property, value)` | nodes with exactly this property value (an IRI or literal) |
| `has(property) { ... }` | nodes with at least one `property` value selected by the nested lens |
| `where { ... }` | keep nodes for which the conditions hold; several conditions are ANDed |
| `exclude { ... }` | remove the nodes selected by the nested lens |
| `anyOf { ... }` | union of the listed selectors |
| `allOf { ... }` | an intersection, for use inside `anyOf` |
| `include(lens)` | reuse a lens built elsewhere |

Every block needs at least one selector (`type`, `include`, `allOf` or
`anyOf`). There is no "everything" lens, and `exclude` is always relative to
the lens it appears in. It never means "everything that is not X".

All class, property and datatype names must be absolute IRIs (`http:`,
`https:` or `urn:`). Anything else is rejected before a query is built.

The same model is also available as plain functions (`LensDsl.type`,
`LensDsl.and`, `LensDsl.or`, `LensDsl.minus`, `LensDsl.filter`, `LensDsl.eq`,
`LensDsl.exists`) if you prefer to assemble lenses programmatically.

## Composing lenses

Lenses are immutable values, so define the building blocks once and combine
them:

```kotlin
val companies = lens { type("urn:ex:Company") }
val active    = lens { include(companies); where { path("urn:ex:active") eq true } }
val inUs      = lens { include(companies); where { path("urn:ex:country") eq "US" } }

val activeUs  = lens { include(active); include(inUs) }          // both
val either    = lens { anyOf { include(active); include(inUs) } } // one or the other
val dormant   = lens { include(companies); exclude { include(active) } }
```

You do not need to restructure a composition for speed. Before compiling,
Kastor rewrites it so that a base shared by several parts is read once. The
three lenses above become, in effect:

| You wrote | It runs as |
| --- | --- |
| `active` and `inUs` | companies, filtered by `active = true` and `country = "US"` |
| `active` or `inUs` | companies, filtered by `active = true` or `country = "US"` |
| `companies` minus `active` | companies, filtered by not `active = true` |

See [Optimization](OPTIMIZATION.md) for the full list of rewrites.

## Filter conditions

Inside `where { }`:

```kotlin
where {
    path("urn:ex:age") ge 18                          // one property
    sequence("urn:ex:parent", "urn:ex:name") eq "Ada" // a chain of properties
    inverse("urn:ex:ownerOf") eq Iri("urn:ex:alice")  // follow a property backwards
    alternative("urn:ex:age", "urn:ex:alternateAge") gt 65
    anyOf { path("urn:ex:tier") eq "gold"; path("urn:ex:tier") eq "platinum" }
    not { path("urn:ex:status") eq "closed" }
}
```

Operators are `eq`, `ne`, `lt`, `le`, `gt`, `ge`. Kotlin values become typed
literals: `String` is `xsd:string`, `Int` and `Long` are `xsd:integer`,
`Double` is `xsd:double`, `Boolean` is `xsd:boolean`. Pass an `Iri` or a Kastor
`Literal` for anything else. Paths have at most eight steps.

Three rules decide what a condition means. They matter most with missing and
multi-valued properties:

1. **A comparison holds if at least one value satisfies it.** A node with ages
   12 and 40 matches both `age lt 18` and `age gt 18`.
2. **A missing property makes a comparison false.** A node with no age matches
   neither.
3. **`not` flips the result.** A node with no age *does* match
   `not { path(age) gt 18 }`. This is "no matching value in this data", not
   logical negation over an ontology.

So `not { age gt 18 }` is not the same as `age le 18`: the first includes nodes
with no age, the second does not.

## Running a node lens

```kotlin
import com.geoknoesis.kastor.rdf.lens.KastorLensProviderBridge
import org.geoknoesis.lens.SparqlCompiler

val sparql = SparqlCompiler().select(usCompanies)
val nodes: Set<Iri> = KastorLensProviderBridge(repository).nodes(sparql)
```

The selected nodes must be IRIs; a blank node in the result is an error.

To see what will run, without running it:

```kotlin
val explained = SparqlCompiler().explain(usCompanies)
println(explained.sparql)       // the exact query
println(explained.plan.rules)   // which rewrites were applied
println(explained.plan.lens)    // the rewritten lens
```

`plan.nodesBefore` and `plan.nodesAfter` count lens operators before and after
rewriting. They are not row counts or cost estimates.

## Network lenses

A network lens starts from seed nodes and follows one property for a bounded
number of hops.

```kotlin
val supplyChain = network {
    seeds { type("urn:ex:StartingCompany") }
    predicate = "urn:ex:suppliesTo"
    maxDepth = 2                              // 0..3
    direction = NetworkDirection.OUT          // OUT, IN or BOTH
    traversalNode { type("urn:ex:Company") }  // nodes the walk may pass through
    outputNode { type("urn:ex:Public") }      // nodes kept in the result
    closure = NetworkClosure.TRAVERSED
}

val compiled = NetworkLensCompiler().compile(supplyChain)
val graph = KastorLensProviderBridge(repository).network(compiled.nodes, compiled.edges)
graph.nodes   // Set<Iri>
graph.edges   // Set<Edge(subject, predicate, obj)>
```

| Setting | Meaning |
| --- | --- |
| `seeds` | where walks start (required) |
| `predicate` | the property to follow (required) |
| `maxDepth` | maximum hops, 0 to 3 (required). Depth 0 returns only the seeds. |
| `direction` | `OUT` follows the property forwards, `IN` backwards, `BOTH` either way |
| `traversalNode` | every node on a walk, seeds included, must match this lens |
| `outputNode` | only matching nodes are returned. It does not stop the walk. |
| `traversalEdge(p, v)` | every followed edge must have an RDF reification statement with this property value |
| `outputEdge(p, v)` | same, but only for edges in the result |
| `closure` | which edges are returned (below) |
| `datasetGraph` | run against one named graph |

The two closure modes differ only in the edges:

- `TRAVERSED` returns the edges that a valid walk actually used.
- `INDUCED` returns every `predicate` edge between two result nodes, whether a
  walk used it or not.

In both modes an edge is returned only if both of its ends are in the result
nodes. When several edge conditions are given, a single reification statement
must satisfy all of them.

## Tabular lenses

Tabular lenses turn a node selection into rows:

```kotlin
val companies = lens { type("urn:ex:Company") }
val industry = TabularLens.Column("industry", "urn:ex:industry")

val table = TabularDsl.project(companies, industry, TabularLens.Column("region", "urn:ex:region", required = true))
val total = TabularDsl.count(companies)
val byIndustry = TabularDsl.facet(companies, "urn:ex:industry", includeMissing = true)
val grid = TabularDsl.groupCount(companies, industry, TabularLens.Column("region", "urn:ex:region"))
val averageRevenue = TabularDsl.avg(companies, "urn:ex:revenue", industry)

val result = KastorTabularLensExecutor(repository).execute(byIndustry)
result.columns  // List<String>
result.rows     // List<Map<String, RdfTerm?>>
```

`sum`, `avg`, `min` and `max` are available, optionally grouped by up to three
columns. `KastorTabularLensExecutor.explain(lens)` returns the query without
running it. The exact counting and aggregation rules, especially for
multi-valued and missing properties, are in [Tabular lenses](TABULAR-LENSES.md).

## Saved lens definitions in RDF

Node and network lenses can be stored as RDF and run by IRI. Write them with
`lensDefinitions`, or author the Turtle directly as described in the
[RDF profile](RDF-PROFILE.md):

```kotlin
val definitions = lensDefinitions {
    define("urn:lens:us", usCompanies)
    define("urn:lens:chain", supplyChain)
}
val executor = KastorRdfLensExecutor(repository, definitions)

when (val result = executor.executeValidated(Iri("urn:lens:us"))) {
    is KastorRdfLensExecutor.Result.Nodes -> result.nodes
    is KastorRdfLensExecutor.Result.Network -> result.graph
}
```

| Method | Use it when |
| --- | --- |
| `executeValidated(iri)` | definitions come from outside your code. Checks the whole definitions graph against the bundled SHACL shapes first. |
| `execute(iri)` | definitions are already trusted or validated. Skips SHACL; structural checks still run. |
| `prepareValidated(iri)` / `prepare(iri)` | you will run the same lens more than once (below) |

`has(...)`, `outputNode`, edge conditions and `datasetGraph` have no RDF form
yet. `lensDefinitions` rejects them instead of silently dropping them; compile
such lenses directly from Kotlin.

### Running the same lens repeatedly

`execute` and `executeValidated` load, validate and compile on every call.
For a lens you run often, do that work once:

```kotlin
val prepared = executor.prepareValidated(Iri("urn:lens:us"))
val first = prepared.execute()
val later = prepared.execute(KastorLensProviderBridge.Limits(maxNodes = 500))
```

A prepared lens is a snapshot. If the definitions graph changes, prepare again.

## Limits and failures

Every execution is bounded. Exceeding a bound throws; a partial result is never
returned.

| Where | Default | Setting |
| --- | --- | --- |
| Node results | 5,000 nodes | `KastorLensProviderBridge.Limits(maxNodes = ...)` |
| Network results | 5,000 nodes, 15,000 edges | `Limits(maxNodes = ..., maxEdges = ...)` |
| Table results | 1,000 rows (up to 100,000) | `KastorTabularLensExecutor.Limits(maxRows = ...)` |
| Query time | none | `Limits(timeout = Duration.ofSeconds(...))` |
| Generated query size | 100,000 characters | `NetworkLensCompiler(maxQueryChars = ...)`, `TabularLensCompiler(maxQueryChars = ...)` |
| Network depth | 3 | fixed |

Set a timeout for anything user-facing. The row limits protect your
application's memory, but only a timeout stops a query that is slow inside the
repository.

`KastorRdfLensExecutor` and `KastorTabularLensExecutor` also send the limit to
the repository, so it can stop early. If you pass your own query string to
`KastorLensProviderBridge` directly, the limit is only checked while reading.

Invalid input fails with `IllegalArgumentException` before any query runs:
relative or unsafe IRIs, empty blocks, a depth outside 0..3, an ordered
comparison against an IRI, or a duplicate column name.

## Performance guide

What Kastor does for you:

- **Shared bases are read once.** Compose freely; see
  [Composing lenses](#composing-lenses).
- **Walks start from the seeds.** Network queries are written so the repository
  expands outward from the seed nodes instead of scanning every edge.
- **Nested tests run on bound values.** `has(p) { ... }` and multi-step
  `sequence(...)` paths are evaluated one step at a time from the node being
  tested.

What you control:

- **Make seeds selective.** Network cost grows with the number of seeds times
  the fan-out per hop. Twenty seeds at depth 3 is cheap; every company at
  depth 3 is not.
- **Use the smallest depth that answers the question.** Each extra hop
  multiplies the work by the average number of links per node.
- **Prefer guards without `anyOf`.** A `traversalNode` or `outputNode` lens with
  no union is joined directly into the walk. One with a union still works, but
  is checked per node afterwards.
- **`BOTH` costs about twice `OUT` or `IN`** per hop.
- **Prepare lenses you run repeatedly**, and always set limits and a timeout.
- **Check the query when something is slow.** `explain()` shows exactly what is
  sent to the repository.

### Measured effect

Median wall-clock time on in-memory repositories with 20,000 nodes and about
125,000 triples, before and after the composition and query-shape work
described in [Optimization](OPTIMIZATION.md). Network cases use 20 seeds.

| Case | Jena before | Jena after | RDF4J before | RDF4J after |
| --- | ---: | ---: | ---: | ---: |
| Three lenses sharing a base, intersected | 273 ms | 238 ms | 118 ms | 113 ms |
| Two filters on one base, unioned | 209 ms | 177 ms | 80 ms | 70 ms |
| Base minus filtered base | 97 ms | 88 ms | 54 ms | 36 ms |
| `has(link) { filtered class }` | 216 ms | 222 ms | 44,502 ms | 54 ms |
| Filter on a two-step sequence path | 69 ms | 71 ms | 28,365 ms | 31 ms |
| Network, depth 3, no guard, traversed | 758 ms | 24 ms | 5,778 ms | 26 ms |
| Network, depth 3, class guard, traversed | 1,198 ms | 8 ms | 1,294 ms | 24 ms |
| Network, depth 2, both directions, class guard | 8 ms | 9 ms | 397 ms | 26 ms |
| Network, depth 2, no guard, induced | 16,101 ms | 47 ms | failed | 64 ms |
| Network, depth 3, class guard, induced | 24,600 ms | 10 ms | 4,123 ms | 32 ms |

How to read this:

- The large gains are in network lenses and in nested tests on RDF4J.
- The first three rows are within run-to-run noise on this machine (the same
  case varied by up to a factor of two between runs). The rewrites remove
  repeated scans, but at this data size the per-node filter tests dominate.
- "failed" is a provider error on RDF4J, most likely the benchmark's 45-second
  timeout.
- These are in-memory stores on one developer machine. Use them as relative
  evidence, not as predictions for your repository.

To measure on your own machine:

```bash
LENS_BENCHMARK=1 ./gradlew :rdf:lens:test --rerun --tests '*LensQueryBenchmark*'
```

Optional environment variables: `LENS_BENCHMARK_NODES` (default 20000),
`LENS_BENCHMARK_PROVIDER` (`jena` or `rdf4j`) and `LENS_BENCHMARK_OUT` (a file
to append results to).

## Security

- Lenses only accept typed values. IRIs and literals are validated and escaped;
  there is no way to pass a SPARQL fragment.
- **The library does not authorize access.** Hand it a repository (and, for
  networks, a `datasetGraph`) the caller is allowed to read.
- Lens definitions loaded from RDF are never followed to other documents:
  `owl:imports` is not dereferenced.
- Treat definitions from outside your code as untrusted: use
  `executeValidated` or `prepareValidated`, and set limits and a timeout.

## Limitations

- Network depth is limited to 3 and follows a single property.
- No repeated paths (`*`, `+`), no custom functions, no free-form SPARQL.
- Results are bounded and materialized. There is no streaming or paging API.
- Tabular lenses are Kotlin-only; they cannot be saved as RDF yet.
- The optimizer rewrites the lens and shapes the query. It does not use
  statistics from the repository and makes no cost estimates.
- The RDF vocabulary namespace is provisional and not a standard.

## More detail

- [Optimization](OPTIMIZATION.md): the rewrites and the generated query shapes
- [RDF profile](RDF-PROFILE.md): the RDF form of saved definitions
- [Typed filters and SHACL](TYPED-FILTERS-AND-SHACL.md): filter semantics and validation
- [Tabular lenses](TABULAR-LENSES.md): counting and aggregation rules
