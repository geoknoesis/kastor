# How to Perform RDFS Reasoning

{% include version-banner.md %}

## What you'll learn
- Run RDFS reasoning over a graph
- Inspect inferred triples

## Prerequisites
- Add the reasoning module:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:reasoning:0.1.0")
}
```

## Step 1: Build a graph with class hierarchy

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS

val graph = Rdf.graph {
    val employee = iri("http://example.org/Employee")
    val person = FOAF.Person
    val alice = iri("http://example.org/alice")

    employee - RDFS.subClassOf - person
    alice - RDF.type - employee
}
```

## Step 2: Run the reasoner

```kotlin
import com.geoknoesis.kastor.rdf.reasoning.RdfReasoning
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType

val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
val result = reasoner.reason(graph)

println("Inferred triples: ${result.inferredTriples.size}")
```

## Step 3: Check for inferred types

```kotlin
val alice = iri("http://example.org/alice")
val person = FOAF.Person
val rdfType = RDF.type

val inferredPerson = result.inferredTriples.any { triple ->
    triple.subject == alice && triple.predicate == rdfType && triple.obj == person
}

println("Alice inferred as Person: $inferredPerson")
```

## Expected output

```
Inferred triples: 1
Alice inferred as Person: true
```

