# How to Validate Data with SHACL

{% include version-banner.md %}

## What you'll learn
- Create a data graph and a shapes graph
- Validate data using the SHACL validator
- Inspect validation results

## Prerequisites
- Add the SHACL validation module:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:shacl-validation:0.1.0")
}
```

## Step 1: Create a data graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri

val dataGraph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    alice has "http://xmlns.com/foaf/0.1/name" with "Alice Johnson"
    // Missing age on purpose to trigger a violation
}
```

## Step 2: Create a shapes graph

```kotlin
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.int

val shapesGraph = Rdf.graph {
    val shape = iri("http://example.org/shapes/PersonShape")
    val propertyShape = bnode("ageShape")

    shape - "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" - "http://www.w3.org/ns/shacl#NodeShape"
    shape - "http://www.w3.org/ns/shacl#property" - propertyShape

    propertyShape - "http://www.w3.org/ns/shacl#path" - "http://xmlns.com/foaf/0.1/age"
    propertyShape - "http://www.w3.org/ns/shacl#minCount" - int(1)
    propertyShape - "http://www.w3.org/ns/shacl#datatype" - "http://www.w3.org/2001/XMLSchema#integer"
}
```

## Step 3: Validate the data

```kotlin
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

println("Valid: ${report.isValid}")
report.violations.forEach { violation ->
    println(violation.message)
}
```

## Expected output

```
Valid: false
Property 'http://xmlns.com/foaf/0.1/age' has 0 values, but minimum is 1
```

## Notes
- The memory validator supports basic SHACL Core constraints such as `sh:minCount`, `sh:maxCount`, `sh:datatype`, and `sh:class`.

