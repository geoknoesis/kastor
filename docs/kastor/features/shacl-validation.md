# SHACL Validation

Kastor supports SHACL validation with full **SHACL 1.2** support, including Core features and SPARQL Extensions. Validation is available in two complementary ways:

- **Kastor Gen ValidationContext** for domain materialization and `RdfHandle` validation.
- **Repository-level SHACL validation** via the `rdf/shacl/validation` module.

For the **provider model** (native Kastor engine vs optional adapters to Jena, RDF4J, and others), module layout, and implementation roadmap, see [SHACL validation architecture](../design/shacl-validation-architecture.md). For **performance benchmarking** (JMH harness, ERA-SHACL-Benchmark CLI, baselines), see [SHACL native engine: cross-implementation performance benchmarks](../design/shacl-native-engine-benchmark.md).

## Kastor Gen ValidationContext

Validation is explicit and optional. You decide when validation is enforced by passing a `ValidationContext` during materialization.

### Add a Validation Adapter

Pick the adapter that matches your backend:

```kotlin
dependencies {
    runtimeOnly(project(":kastor-gen:validation-jena"))
    // or
    runtimeOnly(project(":kastor-gen:validation-rdf4j"))
}
```

### Validate During Materialization

```kotlin
val validation = JenaValidation()
val person: Person = rdfRef.asValidatedType(validation)
```

### Validate After Materialization

```kotlin
val validation = JenaValidation()
val person: Person = rdfRef.asType(validation)
person.asRdf().validateOrThrow()
```

## Repository-Level SHACL Validation

Module **`rdf/shacl/validation`**: API reference, **`providerId`** table, **`maxCombinedGraphTriples` / `rdf4jUntrustedInputLimits`**, and cross-engine smoke tests live in the [module README](../../../rdf/shacl/validation/README.md).

Use the `rdf/shacl/validation` module when you want to validate graphs directly (without materialization):

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

// Create shapes using the Kotlin SHACL DSL (recommended; add dependency `com.geoknoesis.kastor:rdf-shacl-dsl`)
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.name) {
            minCount = 1
        }
    }
}

// Or create shapes manually
val shapesGraph = Rdf.graph {
    // ... manual RDF triples
}

// Validate
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { println(it.message) }
}
```

### Conformance semantics

- `report.isValid` mirrors SHACL `sh:conforms`. By default it is `false` if **any** validation result of severity `sh:Violation`, `sh:Warning`, `sh:Info` or a custom severity exists; SHACL 1.2 `sh:Debug` / `sh:Trace` results do not affect conformance.
- `ValidationConfig.conformanceDisallows: Set<Iri>?` is SHACL 1.2 `sh:conformanceDisallows`. It is the set of result severities (`SHACL.Violation`, `SHACL.Warning`, `SHACL.Info`, `SHACL.Debug`, `SHACL.Trace` or a custom severity IRI) whose results make `isValid` false. `null` (the default) applies the SHACL default above. Results of other severities are still reported; they just do not block conformance. For example, `ValidationConfig(conformanceDisallows = setOf(SHACL.Violation))` lets warnings through, including the `sh:Warning` results for undefined recursion and undecidable `sh:targetWhere`. The native engine (`kastor`) honours this setting; the other providers do not read it.
- `report.hasViolations` is the severity-filtered check (Violation/Error only). Use it when warnings should not fail a build.
- Property shapes default to `sh:Violation`; they do not inherit a node shape's severity.
- Deactivated shapes and shapes without constraints accept every node. Literal value nodes can conform to nested shapes (`sh:node`, `sh:and`/`sh:or`/`sh:xone`/`sh:not`, qualified value shapes).
- Repeatable parameters (`sh:pattern`, `sh:hasValue`, bounds, …) produce one constraint per value. Repeating a single-valued parameter (`sh:minCount`, `sh:flags`, `sh:in`, …) is a shape compilation error. `sh:pattern` is compiled once, at shape compile time, and supports the flags `i`, `m`, `s`, `x` and `q`.
- Classes that are also shapes act as implicit class targets. Value comparisons (`sh:lessThan`, bounds, …) follow the SPARQL operator mapping for datatypes, and literals are checked for XSD lexical validity.

### Three-valued conformance

The native engine answers every nested conformance check (`sh:node`, logical constraints, qualified value shapes, `sh:someValue`, `sh:targetWhere`, …) with **conforms**, **fails** or **undefined**, and combines the answers with Kleene logic:

- a shape and `sh:and` fail as soon as one part definitely fails;
- `sh:or` and `sh:someValue` conform as soon as one part definitely conforms;
- an answer is undefined only when it really depends on an undefined answer.

As a result, reports never depend on the order of operands, constraints or targets. When one value node is undefined, the definite violations of the other value nodes are still reported, and the undefined value adds a `sh:Warning` result. Qualified value counts use a lower bound (values that definitely conform) and an upper bound (values that conform or are undefined). A count is undefined only when the two bounds lead to different outcomes.

Undefined answers only come from recursive shapes, described next.

### Recursive shapes

SHACL does not define recursive shapes. At compile time, the native engine finds the shapes that can reach themselves (the strongly connected components of the shape dependency graph), because only those can recurse over data. It then applies these rules.

**Monotone recursion conforms (greatest fixpoint).** A shape that depends on itself only through `sh:node`, `sh:and`, `sh:property`, `sh:or`, `sh:someValue` or `sh:qualifiedMinCount` is assumed to conform until one of its constraints fails. Valid cyclic data therefore conforms. `sh:shape`, `sh:memberShape`, `sh:reifierShape` and `sh:nodeByExpression` also count as monotone.

```turtle
# Shapes: recursion through sh:or
ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
  sh:property [ sh:path ex:knows ; sh:or ( ex:NamedShape ex:PersonShape ) ] .
ex:NamedShape sh:property [ sh:path ex:name ; sh:minCount 1 ] .

# Data: a cycle. Conforms, also with strictMode = true.
ex:a a ex:Person ; ex:knows ex:b .
ex:b a ex:Person ; ex:knows ex:a .
```

A cycle can still fail. If a node on it definitely breaks a constraint (for example, a `sh:minCount` on `ex:knows` at the end of a chain), the nodes that depend on it get ordinary `sh:Violation` results.

**Recursion through non-monotone operators is undefined.** Consider a dependency cycle that passes through `sh:not`, `sh:xone`, `sh:qualifiedMaxCount` or the sibling exclusion of `sh:qualifiedValueShapesDisjoint true`. It has no defined answer. Each constraint whose outcome depends on that answer produces a `sh:Warning` result stating that the recursive dependency is undefined. Under the default `conformanceDisallows`, this warning makes `report.isValid` false. With `ValidationConfig(strictMode = true)`, validation throws `ShaclValidationException` instead. If a non-recursive part of the shape already fails, that failure decides the outcome and no undefined result is produced.

```turtle
# Shapes: ex:S depends on itself through sh:not
ex:S a sh:NodeShape ; sh:targetNode ex:x ;
  sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
  sh:property [ sh:path ex:self ; sh:not ex:S ] .

# Data: one blocking sh:Warning result ("... is undefined"); strictMode throws.
ex:x ex:self ex:x ; ex:name "x" .
```

**Deep data is stack-safe.** Recursive shapes are solved with an explicit worklist, not with JVM stack proportional to the data. For example, the following 10,000-node chain validates. A missing name at its tail is reported for every node of the chain.

```kotlin
val chain = Rdf.graph {
    for (i in 0 until 10_000) {
        val p = iri("http://example.org/p$i")
        p - RDF.type - iri("http://example.org/Person")
        p - iri("http://example.org/name") - string("person $i")
        if (i + 1 < 10_000) p - iri("http://example.org/knows") - iri("http://example.org/p${i + 1}")
    }
}
// PersonShape: sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] (plus a name constraint)
val report = ShaclValidation.validator().validate(chain, shapesGraph)
```

**`maxRecursionDepth` limits non-recursive nesting only.** `ValidationConfig.maxRecursionDepth` (default `64`) limits how deeply shapes that are **not** recursive can nest through `sh:node`, logical constraints and similar references. Recursive shapes are solved as described above and are not bound by it. Exceeding the limit throws `ShaclValidationException`.

```kotlin
val validator = ShaclValidation.validator(ValidationConfig(maxRecursionDepth = 128))
```

### `sh:targetWhere`

The native engine checks every node of the data graph against the membership shape of `sh:targetWhere`. It first prunes candidates using the membership node shape's own constraints:
- with `sh:class`, only instances of that class are checked;
- with `sh:nodeKind`, the node kinds it excludes (literals or non-literals) are skipped;
- with `sh:datatype`, only literals are checked.

When a candidate's membership is undefined (recursion through a non-monotone operator), the report gets a `sh:Warning` result for that candidate. The warning blocks conformance under the default `conformanceDisallows`. With `strictMode = true`, validation throws `ShaclValidationException`. SPARQL node expressions used as `sh:targetWhere` values are not supported; see [Unsupported features](#unsupported-features).

### Report contents

`ValidationViolation` carries the full result path (`resultPathNode`, plus `resultPathTriples` for blank-node paths), the shape's `sh:message` values with language tags (`resultMessages`) and `sourceConstraint` (for example, the SHACL-SPARQL constraint node). `toShaclValidationReportRdf()` emits `sh:resultPath`, `sh:resultMessage` and `sh:sourceConstraint`, emits `sh:value` only for components that define it, and emits one `sh:closed` result per offending triple.

### SHACL-SPARQL

`sh:sparql` constraints need a SPARQL engine **at runtime**: add `rdf-jena` or `rdf-rdf4j`. Without one, validation fails with a `ShaclValidationException` naming those modules, and the native provider's capability flags report SHACL-SPARQL as unsupported. Queries:

- run over the **data graph** (see [where queries run](#where-queries-run));
- honour `sh:prefixes`/`sh:declare`, `$PATH`, `sh:deactivated` and message templates;
- pre-bind `$this`, `$currentShape` and `$shapesGraph`;
- produce one result per solution row.

#### Where queries run

When you validate a SPARQL-capable dataset, such as a repository passed to `validateDataset`, queries run on it **in place** only if the dataset lists **no named graphs**. Otherwise the engine copies the data graph into a private in-memory repository once per validation run, and all SPARQL constraints of that run share the copy. This also happens when validating a plain graph. The reason is that a query without a dataset clause may see more than the default graph: on RDF4J it sees the union of all contexts. SHACL-SPARQL constraints must see exactly the data graph that the other constraints validate. Each kind of fallback is logged once per dataset class. The copy is not reused across runs; to validate a large graph repeatedly, validate a repository without named graphs in place.

#### Pre-binding

The engine pre-binds variables itself before the query reaches the provider, so the result is the same on every provider, including inside sub-queries:

- IRIs and literals are substituted **textually** into the query text. In a `SELECT` projection a value becomes `(term AS ?fresh)`, and `BOUND(?var)` on a substituted variable becomes `true`.
- Values with no equivalent SPARQL syntax are passed to the provider as initial bindings: blank nodes, triple terms, directional language strings, and IRIs containing characters that are illegal in an `IRIREF`.
- When a query uses `$shapesGraph`, the shapes graph is loaded as a named graph of the private copy. The caller's dataset is never modified.

A query that breaks the SHACL-SPARQL pre-binding restrictions is rejected when the shapes are compiled. The restrictions are:
- no `MINUS`, `SERVICE` or `VALUES`;
- no `AS` that re-binds a pre-bound variable;
- a query that uses `$this` must project `$this` from each sub-query.

Validation then throws `ShaclValidationException` ("SHACL compile failed: ..."), with a `SparqlPreBindingRestrictionException` as its cause.

### Unsupported features

The native engine recognises some SHACL constructs that it cannot evaluate. By default (`ValidationConfig.unsupportedFeatures = UnsupportedFeatureHandling.FAIL`), it rejects the shapes graph before validating anything, so these constraints are never silently skipped.

| `UnsupportedShaclFeature` | Constructs |
|---------------------------|------------|
| `SPARQL_CONSTRAINT_COMPONENT` | SHACL-SPARQL constraint components (`sh:validator`, `sh:nodeValidator`, `sh:propertyValidator`) used by a shape |
| `NODE_EXPRESSION` | SHACL 1.2 node expressions: `sh:values`, `sh:expression`, computed `sh:targetNode` / `sh:nodeByExpression` values |
| `SPARQL_NODE_EXPRESSION` | SPARQL node expressions (`sh:select`, `sh:sparqlExpr`), including SPARQL expressions used as targets (`sh:targetWhere`, `sh:targetNode`) |
| `SHACL_FUNCTION` | SHACL 1.2 functions (`sh:bodyExpression`) called from a SPARQL query |
| `CUSTOM_TARGET` | SPARQL-based or custom targets (`sh:target`) |

Plain `sh:sparql` constraints (see above) are supported.

In the failure, `ShaclValidationException` wraps an `UnsupportedShaclFeatureException`. Its message names each offending construct, and its `features` property lists the categories:

```kotlin
try {
    validator.validate(dataGraph, shapesGraph)
} catch (e: ShaclValidationException) {
    val unsupported = e.cause as? UnsupportedShaclFeatureException ?: throw e
    println("Unsupported: ${unsupported.features}")
}
```

To skip these constructs instead, set `ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)`. Each skipped construct is listed in `report.warnings` ("Unsupported SHACL feature ignored: ..."). These are report warnings, not validation results, so they do not change `isValid`.

Detection only inspects declared and implicit shapes, plus the nodes reachable from them through shape-valued parameters. Data triples that share the shapes graph are therefore never mistaken for expressions. For example, `validate(g, g)` works when `g` has blank-node `sh:targetNode` values that carry data triples. A blank `sh:targetNode` counts as a node expression only when its own triples use expression syntax.

### W3C conformance

CI runs the **full W3C SHACL 1.2 test suite** (core, node-expression and SPARQL manifests) against the native engine. The suite comes from a pinned `w3c/data-shapes` commit (`94d8bc2`, 166 cases). To run it locally:

```bash
python scripts/fetch-conformance-data.py --only shacl
./gradlew :rdf:shacl-validation:w3cConformanceTest
```

The task fails instead of skipping when the suite is missing, and every case is executed. The harness checks failures strictly:
- Each known deviation in `W3cKnownDeviations` has an `UnsupportedShaclFeature` category and must fail with an `UnsupportedShaclFeatureException` for exactly that category. At the pinned commit there are 11: 4 SPARQL constraint components, 1 node expression, 3 SPARQL node expressions and 3 SHACL functions.
- `sht:Failure` cases must fail with their expected category (a pre-binding restriction or a shapes compile failure), never with an unsupported feature.
- `sh:conformanceDisallows` cases check the engine's own `isValid` and exported `sh:conforms`.

See the [module README](../../../rdf/shacl/validation/README.md).

### RDF4J validator

With `providerId = "rdf4j"`, `isValid` comes from the engine's `sh:conforms`, read before any `maxViolations` cap is applied. `validateResource` validates the whole graph and then keeps only the results for the requested focus node.

> **Tip**: Use the [SHACL DSL](../api/shacl-dsl-guide.md) to create shapes graphs more easily. The DSL supports all SHACL 1.2 features including:
> - **SHACL 1.2 Core**: `targetWhere` with node expressions, `shape` targets, `singleLine` constraint, `reifierShape` and `reificationRequired` for RDF-star
> - **SHACL 1.2 SPARQL Extensions**: SPARQL-based constraints using SELECT queries (`sparqlAsk` was removed: SHACL-SPARQL constraints cannot use ASK)
>
> See [How to Create SHACL Shapes](../guides/how-to-create-shacl-shapes.md) for examples. For **bundled ontology-quality shapes**, the `onto-qa` CLI, and the optional embedding tier, see [How to Check Ontology Quality](../guides/how-to-ontology-quality.md).

## Notes

- `ValidationContext` is only enforced when provided.
- `ValidationResult.NotConfigured` is returned when no context is available.
- Repository-level validation remains independent of Kastor Gen materialization.
