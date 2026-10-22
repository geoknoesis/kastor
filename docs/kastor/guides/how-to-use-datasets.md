# How to Use Datasets

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** what a **dataset** is (default vs named graphs) → [Datasets](../concepts/datasets.md), [**Glossary**](../concepts/glossary.md). **Reference:** `Dataset`, `RdfRepository` query APIs → [Core API](../api/core-api.md).

## Problem

Combine multiple graph sources and run **SPARQL** over the **default graph** (union) and/or specific **named graphs** via `GRAPH { ... }`.

For explicit content units with an administrative hierarchy and imports, see [RDF-H holon scopes](#rdf-h-holon-scopes).

## Prerequisites

- **`rdf-core`** plus at least one provider (examples below use `Rdf.memory()` repositories).

## Steps

### Step 1: Prepare repositories

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

val peopleRepo = Rdf.memory()
val orgRepo = Rdf.memory()

peopleRepo.add {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice"
}

orgRepo.add {
    val acme = iri("http://example.org/org/acme")
    acme has FOAF.name with "ACME Corp"
}
```

### Step 2: Build a dataset

```kotlin
val peopleGraph = iri("http://example.org/graphs/people")
val orgGraph = iri("http://example.org/graphs/orgs")

val dataset = Dataset {
    // Default graph is the union of these graphs
    defaultGraph(peopleRepo.defaultGraph)

    // Named graphs for explicit GRAPH queries
    namedGraph(peopleGraph, peopleRepo, null)
    namedGraph(orgGraph, orgRepo, null)
}
```

### Step 3: Query the default graph

```kotlin
val defaultResults = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        ?person ${FOAF.name} ?name .
    }
"""))
```

### Step 4: Query a named graph

```kotlin
val namedResults = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        GRAPH ${peopleGraph} {
            ?person ${FOAF.name} ?name .
        }
    }
"""))
```

### Step 5: Inspect dataset contents

```kotlin
val names = dataset.listNamedGraphs()
println("Named graphs: $names")
println("Has people graph: ${dataset.hasNamedGraph(peopleGraph)}")
```

## Validation

Example:

```
Named graphs: [http://example.org/graphs/people, http://example.org/graphs/orgs]
Has people graph: true
```

## Troubleshooting

- **Triple “missing” in default graph** — confirm it was merged via `defaultGraph(...)`; triples only in a named graph need a `GRAPH` clause unless your union semantics include them by design.
- **Writes ignored** — `Dataset` here is **read-oriented** for federation; mutate underlying **`RdfRepository`** instances, then rebuild or refresh views.

## Related tasks

- [Named graphs](how-to-named-graphs.md)
- [SPARQL bindings & Flow](how-to-sparql-bindings-and-flows.md)
- [Parse RDF](how-to-parse-rdf.md)

## Implementation notes

- `Dataset` composition is **read‑only** at this layer for the pattern above; use **`RdfRepository`** for transactional edits.
- The default graph supplied via `defaultGraph(...)` reflects the **union** you attach there (see [Datasets](../concepts/datasets.md)).
- **`RdfRepository`** implements **`Dataset`** where applicable — you can often query a single repo as a dataset without wrapping.


## RDF-H holon scopes

Add the optional module alongside your chosen RDF provider:

```kotlin
implementation("com.geoknoesis.kastor:rdf-holon:0.3.0-SNAPSHOT")
runtimeOnly("com.geoknoesis.kastor:rdf-jena:0.3.0-SNAPSHOT")
```

RDF-H 0.8 is a community draft. `RDFH` vocabulary constants and the `h:` QName prefix are included
in `rdf-core`; `rdf-holon` adds the processing model. Turtle-H block syntax and SPARQL-H query syntax
are separate optional languages; this module uses ordinary RDF serialization and standard SPARQL.

### Author holons with the Kotlin DSL

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.holon.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

val city = Iri("https://example.org/City")
val district = Iri("https://example.org/District")
val alice = Iri("https://example.org/Alice")
val bob = Iri("https://example.org/Bob")

val catalog = holons(sourceId = "catalog-v1") {
  holon(city) {
    content { alice has FOAF.knows with bob }
  }
  holon(district) {
    parent(city)
    content { bob has FOAF.name with "Bob" }
  }
}

val cityHolon: Holon = catalog[city] // Also catalog.holon(city).
check(cityHolon.content.size() == 1)
check(cityHolon.aggregated().graph.size() == 2)
cityHolon.asDataset(HolonScope.AGGREGATED).use { dataset ->
  check(dataset.ask(SparqlAskQuery("""
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    ASK { <https://example.org/Alice> foaf:knows ?person . ?person foaf:name "Bob" }
  """.trimIndent())))
}
```

Inside `holon`, `parent(id)`, `imports(id, ...)`, and `importsShapes(graphIri)` write declarations
to M. `content { ... }` uses Kastor's existing triple DSL; repeated blocks append with RDF set
semantics. `content(graph)` copies a graph immediately. Forward parent/import references are allowed;
undeclared targets, parent cycles and multiple parents fail when the context is built. Repeating a
holon identifier is rejected. An empty `holon(id)` is registered even without a content block.

Construction produces a dataset-profile context without needing a provider. By default each holon's
content graph is named by its identifier, including an explicit mapping for blank-node holons.
`holon(id, graphName = name)` selects another graph; holons using the same graph name see the union
of all content written to it. All supplied nodes and graphs share one blank-node scope; standardize
independently loaded graphs apart before passing them to `content(graph)`.

A `Holon` belongs to one immutable context snapshot. It works with authored contexts and existing
dataset/reifier/mixed contexts. `direct()`, `aggregated()`, `visible()`, `global()`, and `resolve(scope)`
return the normal resolution, including attribution and provenance. `content` reads direct content.
`asDataset(scope, attributed)` creates an owned query dataset that must be closed.
`effectiveShapes(shapes)` and `validate(shapes, scope)` use this holon's explicit shape imports.
Two handles are equal only when both identifier and context snapshot match.

`cityHolon.convert(HolonProfile.REIFIER, HolonPreservation.ASSERTION)` converts the **whole context**,
preserving the registry and the meaning of hierarchy/imports. Inspect the returned conversion report,
then use `report.context[cityHolon.id]` for the converted handle. Operations leave the source snapshot
unchanged. These Kotlin builders do not add Turtle-H or SPARQL-H syntax.

### Resolve and query a scope

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.holon.*

val data = """
  @prefix h: <https://w3id.org/rdf-h#> .
  @prefix ex: <https://example.org/> .
  ex:City a h:Holon ; h:importsShapes ex:CityShapes .
  ex:District a h:Holon ; h:parentHolon ex:City .
  ex:City { ex:Alice ex:knows ex:Bob . }
  ex:District { ex:Bob ex:name "Bob" . }
""".trimIndent()

Rdf.parseDataset(data, RdfFormat.TRIG).use { dataset ->
  val context = HolonContext.fromDataset(dataset, sourceId = "catalog-revision-1")
  val city = Iri("https://example.org/City")
  val resolved = context.resolve(city, HolonScope.AGGREGATED)

  resolved.asDataset().use { queryDataset ->
    val rows = queryDataset.select(SparqlSelectQuery("""
      PREFIX ex: <https://example.org/>
      SELECT ?name WHERE { ex:Alice ex:knows ?person . ?person ex:name ?name }
    """.trimIndent()))
    check(rows.first()?.getString("name") == "Bob")
  }

  val reified = context.convert(HolonProfile.REIFIER, HolonPreservation.ASSERTION)
  check(reified.discardedAssertions.size() == 0)
}
```

`DIRECT` is the default. `AGGREGATED` adds all administrative descendants. `VISIBLE` adds the
selected root's direct imports to the aggregated content. Imports on descendants and on imported targets
are not expanded. An unrelated invalid parent declaration in M fails aggregated/visible requests;
direct requests ignore parent/import validity. Registered but empty holons are valid; unknown ones fail.

The result is an immutable snapshot. It records the source ID, generated snapshot ID, effective M,
profile, holon, scope and filtering flag. For consistent reads of a live repository, `fromDataset`
captures its graphs inside a read transaction. The caller owns the original dataset/repository.
Scope selection grants no authorization. Pass `filtered = true` when the supplied data has been filtered;
the result then describes that filtered view.

### Metadata and reifiers

`fromDataset` defaults M to the default graph. A separately supplied metadata graph is authoritative;
content-only `h:parentHolon`, `h:imports` and registrations never become declarations.
`fromGraph(source, metadata)` requires explicit M for the reifier profile. In that profile,
`RDF.reifies` refers to a `TripleTerm`, and `RDFH.inHolon` files its base triple. Every filed base triple
must be asserted in G, including when the only assertion is in separately supplied M.

Externally loaded M has a separate blank-node namespace by default. Set `metadataSharesSourceScope = true`
only when M is a view of that same source document/dataset. Equal labels from independent files do not
identify the same blank node. `HolonContext.of` is the lower-level entry point for an already assembled
shared-scope dataset, including blank-node graph names. Standardize independently sourced graphs apart
with `BlankNodeScopes` before assembling that input.

Reifier-looking statements in a dataset are ordinary assertions unless `fromDataset(..., mixed = true)`
is requested. Mixed processing reads memberships and base assertions across all source graphs.

### Attribution and query capabilities

`resolved.attributedGraphs` has one entry per selected holon, named by its IRI or blank-node identity,
and retains empty holons. The default scope graph is a set union; a triple filed in two holons occurs once.
`resolved.asDataset(attributed = true)` also exposes IRI-named holons to ordinary `GRAPH ?h` queries.
Without attribution, the query dataset has no named graphs. Joins still span the entire default scope union.
Queries cannot replace the resolved dataset with their own `FROM`/`FROM NAMED` clauses.

Kastor's public query `Dataset` supports IRI graph names. Blank-node attributed names therefore cause
`unsupported-capability` in the bridge. Providers that cannot retain empty named graphs also cause
`unsupported-capability` for attributed selections containing empty holons. The full attributed snapshot
remains available through `attributedGraphs`; graph names are never silently omitted or skolemized.
`GLOBAL` is available for validation/reasoning and is rejected by the query bridge.

### Convert without changing content

`context.convert(HolonProfile.DATASET)` or `.REIFIER` preserves M, registry and every holon's direct
content. Missing blank-node mappings are derived with fresh graph names and reported in `derivedMappings`;
existing mappings are retained. The report lists every discarded source assertion.
`HolonPreservation.ASSERTION` additionally retains all asserted source statements or fails.

Conversion raises `HolonException` with `UNREPRESENTABLE_CONVERSION` when preserving the contract is
impossible. Examples include different contents mapped to one target graph, and retained `h:inHolon`
assertions that would become invalid or additional memberships in a reifier target. Unfiled assertions
are kept outside M and content graphs. Original graph placement and occurrence multiplicity are not
additional preservation guarantees.

### Validate a holon's content

```kotlin
val checked = context.validate(
  holon = city,
  shapes = mapOf(Iri("https://example.org/CityShapes") to cityShapes),
  scope = HolonScope.AGGREGATED,
)
println(checked.report.isValid)
```

Here `context`, `city` and `cityShapes` are existing application inputs. Supply the named shapes
as a separate, shared-scope dataset; these graphs do not enter G. Only H's explicit `h:importsShapes`
values select shapes. Shape imports are single-hop and are not inherited. The effective union is
checked with bundled W3C SHACL-for-SHACL syntax shapes and the native compiler before domain validation.
The W3C syntax graph covers a subset of syntax rules; the installed compiler also checks its supported
constructs. Validation needs an installed Turtle parser for that bundled graph and never fetches imports.
Data-driven `sh:shapesGraph` discovery and automatic `owl:imports` expansion are disabled.

A missing imported graph raises `MISSING_SHAPES_GRAPH`; malformed definitions raise
`ILL_FORMED_SHAPES_GRAPH`; ordinary constraint violations are a normal `ValidationReport` with
`isValid == false`. Simple entailment is the supported regime; other regimes and unsupported SHACL
features fail explicitly. The report retains the resolved scope, shapes graph names and effective shapes.

### Verify an implementation

From the Kastor checkout:

```text
gradlew.bat :rdf:holon:check :rdf:holon:conformanceConfig
```

The adapter configuration is written to `build/modules/rdf/holon/conformance/adapter-config.json`.
From a checkout of the RDF-H suite, run its RDF-H manifest with that configuration and `--require-all`.
The test-only adapter executes conversion and validation; it makes no Turtle-H/SPARQL-H conformance claim.
The module's regression tests additionally check scopes, all processing error identifiers, blank-node
identity, conversion failure cases and query boundaries. See the [RDF-H draft](https://geoknoesis.github.io/rdf-holon/)
and [suite protocol](https://github.com/geoknoesis/rdf-holon/blob/main/tests/README.md).
