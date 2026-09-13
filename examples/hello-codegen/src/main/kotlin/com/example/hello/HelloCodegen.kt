/**
 * Kastor Hello Codegen Example
 *
 * A minimal, working example of Kastor Gen code generation with KSP:
 * - `src/main/resources/person-shape.ttl` defines a SHACL shape for `ex:Person`
 * - the `@file:Rdf(shacl = …)` annotation below makes the Kastor KSP processor generate the
 *   `Person` interface and its RDF-backed `PersonWrapper` into `com.example.hello`
 * - `main` materializes RDF data as a typed `Person`
 *
 * Run: ./gradlew :examples:hello-codegen:run
 */
@file:Rdf(
    shacl = "person-shape.ttl",
    packageName = "com.example.hello",
    validationAnnotations = ValidationAnnotations.NONE,
)

package com.example.hello

import com.geoknoesis.kastor.gen.annotations.Rdf
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.Rdf as RdfApi
import com.geoknoesis.kastor.rdf.vocab.RDF

fun main() {
    println("=== Kastor Hello Codegen ===")

    val repo = RdfApi.memory()
    val alice = Iri("http://example.org/alice")
    repo.add {
        alice - RDF.type - Iri("http://example.org/Person")
        alice - Iri("http://example.org/name") - "Alice"
        alice - Iri("http://example.org/age") - 30
    }

    // `Person` is generated at build time from person-shape.ttl.
    val person: Person = repo.defaultGraph.materialize(alice)
    println("Name: ${person.name}, age: ${person.age}")

    println("=== Done ===")
}
