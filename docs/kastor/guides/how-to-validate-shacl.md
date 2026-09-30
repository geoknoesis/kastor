# How to Validate Data with SHACL

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** what SHACL is for → [SHACL validation feature](../features/shacl-validation.md), [**Glossary**](../concepts/glossary.md) (**shape**, **focus node**). **Reference:** [SHACL DSL](../api/shacl-dsl-guide.md), validators API.

## Problem

You have **data** and **SHACL shapes** as RDF graphs and need a **validation report** (conforms / violations).

## Prerequisites

Add **`com.geoknoesis.kastor:rdf-shacl-validation`** (in this monorepo: **`project(":rdf:shacl-validation")`**) aligned with your other Kastor artifacts:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:rdf-shacl-validation:0.3.0-SNAPSHOT")
}
```

Plus **`rdf-core`** and a standard provider (`rdf-jena` / `rdf-rdf4j`) for graphs unless you only use in-memory APIs bundled with tests. If you create shapes with the Kotlin **`shacl { }`** DSL (this guide’s recommended path), add **`rdf-shacl-dsl`** (`com.geoknoesis.kastor:rdf-shacl-dsl`) — see [SHACL DSL Guide](../api/shacl-dsl-guide.md).

## Steps

### Step 1: Create a data graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

val dataGraph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice Johnson"
    // Missing age on purpose to trigger a violation
}
```

### Step 2: Create a shapes graph

You can create shapes graphs using either the **SHACL DSL** (recommended) or manual RDF triples.

### Using SHACL DSL (Recommended)

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.XSD

val shapesGraph = shacl {
    nodeShape("http://example.org/shapes/PersonShape") {
        targetClass(FOAF.Person)
        
        property(FOAF.age) {
            minCount = 1
            datatype = XSD.integer
        }
    }
}
```

Complete DSL syntax and constraints → **Reference:** [SHACL DSL Guide](../api/shacl-dsl-guide.md).

### Using Manual RDF (Alternative)

```kotlin
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.int
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD

val shapesGraph = Rdf.graph {
    val shape = iri("http://example.org/shapes/PersonShape")
    val propertyShape = bnode("ageShape")

    shape - RDF.type - SHACL.NodeShape
    shape - SHACL.property - propertyShape

    propertyShape - SHACL.path - FOAF.age
    propertyShape - SHACL.minCount - int(1)
    propertyShape - SHACL.datatype - XSD.integer
}
```

### Step 3: Validate the data

```kotlin
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

println("Valid: ${report.isValid}")
report.violations.forEach { violation ->
    println(violation.message)
}
```

## Validation

You should see a failed conformance with at least one violation message, for example:

```
Valid: false
Property 'http://xmlns.com/foaf/0.1/age' has 0 values, but minimum is 1
```

## Troubleshooting

- **Classpath / missing validator** — ensure `rdf-shacl-validation` plus `rdf-core` and a provider are dependencies.
- **`isValid` is false but there are no errors** — `isValid` is SHACL `sh:conforms`, so by default Warning and Info results also make it false. Use `report.hasViolations` for a Violation-only check. To make the native engine itself ignore those severities, set `ValidationConfig.conformanceDisallows`; see [Conformance semantics](../features/shacl-validation.md#conformance-semantics).
- **A result with `isUndefinedRecursion == true` says a recursive shape dependency is undefined** — a shape depends on itself through `sh:not`, `sh:xone`, `sh:qualifiedMaxCount` or disjoint qualified value shapes. SHACL gives such a cycle no answer. Restructure the shapes so the recursion goes only through monotone operators (`sh:node`, `sh:and`, `sh:property`, `sh:or`, `sh:someValue`, `sh:qualifiedMinCount`); those cycles conform when the data is valid. With `strictMode = true`, this case throws instead. See [Recursive shapes](../features/shacl-validation.md#recursive-shapes).
- **`sh:sparql` constraints fail with `ShaclValidationException`** — add `rdf-jena` or `rdf-rdf4j` to the runtime classpath; SHACL-SPARQL needs a SPARQL engine.
- **`ShaclValidationException: SHACL compile failed: Unsupported SHACL feature(s) ...`** — the shapes use constructs the native engine cannot evaluate: SPARQL constraint components, `sh:expression`, `sh:values`, SPARQL expressions as targets, and similar. The cause is an `UnsupportedShaclFeatureException` whose `features` lists the categories. Set `ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)` to skip them; each skipped construct is listed in `report.warnings`. See [Unsupported features](../features/shacl-validation.md#unsupported-features).
- **The cause is `SparqlPreBindingRestrictionException`** — a SHACL-SPARQL query uses `MINUS`, `SERVICE`, `VALUES`, re-binds a pre-bound variable with `AS`, or has a sub-query that does not project `$this`. Rewrite the query.
- **`maxRecursionDepth` exceeded** — the limit applies only to deeply nested **non-recursive** shapes. Raise `ValidationConfig.maxRecursionDepth` (default `64`). Recursive shapes are not bound by it.
- **Unexpected conformance** — check **targets** (`sh:targetClass`, focus nodes) against your instance IRIs; see [SHACL feature](../features/shacl-validation.md).

Prefer the [SHACL DSL](../api/shacl-dsl-guide.md) over hand-authored constraint triples for maintainability.

## Related tasks

- [Create SHACL shapes](how-to-create-shacl-shapes.md)
- [Check ontology quality](how-to-ontology-quality.md)
- [Parse RDF](how-to-parse-rdf.md)

