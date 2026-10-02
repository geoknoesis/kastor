package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Graph handles of a Kastor-created RDF4J repository carry an identity (`equals`) and a modification stamp, so the
 * validation cache serves `repository.getGraph(name)` handles - a new object per call - without reading or hashing
 * the graph again, and replaces the cached state when the graph changes.
 */
class Rdf4jRepositoryGraphCacheTest {
    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:minCount 1 ] .
    """.trimIndent()

    private fun people(vararg names: String): List<RdfTriple> = names.flatMap { p ->
        listOf(RdfTriple(ex(p), RDF.type, ex("Person")), RdfTriple(ex(p), ex("name"), Literal(p)))
    }

    @Test
    fun `fresh handles of a repository graph hit the cache without a digest and reload once after a change`() {
        val repo = Rdf4jRepository.MemoryRepository()
        try {
            val name = ex("people")
            val names = Array(30) { "p$it" }
            repo.editGraph(name).addTriples(people(*names))
            assertTrue(repo.getGraph(name) is VersionedRdfGraph, "a Kastor-created repository graph has a stamp")
            assertEquals(repo.getGraph(name), repo.getGraph(name), "handles of one graph are equal")

            Rdf4jValidation.fromTurtle(shapes).use { v ->
                names.forEach { assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex(it))) }
                assertEquals(1, v.loadCount, "the graph is loaded once, not once per node")
                assertEquals(1, v.digestCount, "a stamped graph is digested when it is loaded, never on a hit")
                assertEquals(1, v.readCount)
                assertEquals(1, v.cachedGraphCount())

                repo.editGraph(name).removeTriple(RdfTriple(ex("p3"), ex("name"), Literal("p3")))
                assertTrue(v.validate(repo.getGraph(name), ex("p3")) is ValidationResult.Violations, "the change is detected")
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("p4")))
                assertEquals(2, v.loadCount)
                assertEquals(2, v.digestCount)
                assertEquals(2, v.readCount)

                // A write to another graph moves the repository-wide stamp: this graph is read once to find out
                // that it did not change, and its store is kept.
                repo.editGraph(ex("other")).addTriples(people("z"))
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("p4")))
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("p5")))
                assertEquals(2, v.loadCount, "a write to another graph does not reload this one")
                assertEquals(3, v.readCount)
                assertEquals(1, v.cachedGraphCount(), "the changed graph replaces its entry")
            }
        } finally {
            repo.close()
        }
    }
}
