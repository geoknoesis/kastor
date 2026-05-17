# How to Perform RDFS Reasoning

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** [Reasoning feature](../features/reasoning.md), [RDFS](https://www.w3.org/TR/rdf-schema/) (external). **Reference:** `RdfReasoning`, `ReasonerType`.

## Problem

Given an asserted graph, obtain **inferred triples** under **RDFS** (or another supported profile) for downstream querying or validation.

## Prerequisites

```kotlin
dependencies {
    implementation(platform("com.geoknoesis.kastor:kastor-bom:0.2.0"))
    implementation("com.geoknoesis.kastor:rdf-core")
    implementation("com.geoknoesis.kastor:reasoning")
}
```

The **`reasoning`** artifact gives you **`RdfReasoning`**, **`ReasonerType`**, and the built-in **memory** reasoner provider.

If you use **Jena** or **RDF4J** on the classpath and want their **`RdfReasonerProvider`** implementations (SPI discovery or direct **`JenaReasonerProvider`** / **`Rdf4jReasonerProvider`** types), add:

```kotlin
    implementation("com.geoknoesis.kastor:jena-reasoning")   // optional, with jena store jar
    implementation("com.geoknoesis.kastor:rdf4j-reasoning") // optional, with rdf4j store jar
```

These are **not** transitive from **`jena`** / **`rdf4j`** alone—see [Repository architecture — Dependency profiles](../concepts/architecture.md#dependency-profiles-gradle).

## Steps

### Step 1: Build a graph with class hierarchy

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

### Step 2: Run the reasoner

```kotlin
import com.geoknoesis.kastor.rdf.reasoning.RdfReasoning
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType

val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
val result = reasoner.reason(graph)

println("Inferred triples: ${result.inferredTriples.size}")
```

### Step 3: Check for inferred types

```kotlin
val alice = iri("http://example.org/alice")
val person = FOAF.Person
val rdfType = RDF.type

val inferredPerson = result.inferredTriples.any { triple ->
    triple.subject == alice && triple.predicate == rdfType && triple.obj == person
}

println("Alice inferred as Person: $inferredPerson")
```

## Validation

```
Inferred triples: 1
Alice inferred as Person: true
```

## Troubleshooting

- **Empty inferences** — confirm `rdfs:subClassOf` / `rdf:type` edges use the vocab IRIs you expect; OWL-heavy ontologies may need a different reasoner profile (**Reference:** [Reasoning feature](../features/reasoning.md)).

## Related tasks

- [Validate with SHACL](how-to-validate-shacl.md) (after materializing if your pipeline merges inferences into the data graph)
- [Ontology quality](how-to-ontology-quality.md)
