# How to Validate Data with SHACL

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** what SHACL is for → [SHACL validation feature](../features/shacl-validation.md), [**Glossary**](../concepts/glossary.md) (**shape**, **focus node**). **Reference:** [SHACL DSL](../api/shacl-dsl-guide.md), validators API.

## Problem

You have **data** and **SHACL shapes** as RDF graphs and need a **validation report** (conforms / violations).

## Prerequisites

Add **`com.geoknoesis.kastor:shacl-validation`** (in this monorepo: **`project(":rdf:shacl-validation")`**) aligned with your other Kastor artifacts (`0.2.0` at time of writing):

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:shacl-validation:0.2.0")
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

- **Classpath / missing validator** — ensure `shacl-validation` plus `rdf-core` and a provider are dependencies.
- **Unexpected conformance** — check **targets** (`sh:targetClass`, focus nodes) against your instance IRIs; see [SHACL feature](../features/shacl-validation.md).

Prefer the [SHACL DSL](../api/shacl-dsl-guide.md) over hand-authored constraint triples for maintainability.

## Related tasks

- [Create SHACL shapes](how-to-create-shacl-shapes.md)
- [Check ontology quality](how-to-ontology-quality.md)
- [Parse RDF](how-to-parse-rdf.md)

