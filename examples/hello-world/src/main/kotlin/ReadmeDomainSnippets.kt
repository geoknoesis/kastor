/*
 * Compiled mirror of README.md "Quick Start -> Domain Object Mapping" (see ReadmeSnippets.kt).
 * Compile-only: running it needs KSP-generated wrappers (see examples/hello-codegen).
 */
@file:Suppress("unused", "UNUSED_VARIABLE")

import com.geoknoesis.kastor.gen.annotations.Rdf
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.gen.runtime.materializeIn
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.iri

@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String

    @Rdf(iri = "http://xmlns.com/foaf/0.1/age")
    val age: Int
}

fun readmeDomainMapping(repo: RdfRepository) {
    val person: Person = repo.materialize(iri("http://example.org/person"))
    val viaGraph: Person = repo.defaultGraph.materialize(iri("http://example.org/person"))
    val viaTerm: Person = iri("http://example.org/person").materializeIn(repo.defaultGraph)
    println("Name: ${person.name}, Age: ${person.age}")
}
