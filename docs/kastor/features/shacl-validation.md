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

- `report.isValid` mirrors SHACL `sh:conforms`: it is `false` if **any** validation result of severity `sh:Violation`, `sh:Warning`, `sh:Info` or a custom severity exists. SHACL 1.2 `sh:Debug` / `sh:Trace` results do not affect conformance.
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

- run over the **data graph**;
- honour `sh:prefixes`/`sh:declare`, `$PATH`, `sh:deactivated` and message templates;
- pre-bind `$this`, `$currentShape` and `$shapesGraph`;
- produce one result per solution row.

SPARQL-based constraint components and SHACL 1.2 node expressions are not supported.

### W3C conformance

The native engine runs the W3C SHACL 1.2 test suite: 163 cases, 154 pass. The 9 known deviations (4 SPARQL-based constraint components, 2 SHACL 1.2 node expressions, 3 SPARQL node expressions) are listed with justifications in `W3cKnownDeviations`; see the [module README](../../../rdf/shacl/validation/README.md).

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
