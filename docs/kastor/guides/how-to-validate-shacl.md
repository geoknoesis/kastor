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
import com.geoknoesis.kastor.rdf.vocab.FOAF

val dataGraph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice Johnson"
    // Missing age on purpose to trigger a violation
}
```

## Step 2: Create a shapes graph

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

The SHACL DSL is more readable and type-safe. See the [SHACL DSL Guide](../api/shacl-dsl-guide.md) for complete documentation.

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
- For creating shapes graphs, prefer the [SHACL DSL](../api/shacl-dsl-guide.md) over manual RDF for better readability and type safety.

