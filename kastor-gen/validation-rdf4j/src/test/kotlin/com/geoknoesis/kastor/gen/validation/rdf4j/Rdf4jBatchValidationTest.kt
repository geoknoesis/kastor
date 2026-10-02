@file:OptIn(com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.HandleEqualGraph
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.gen.runtime.RdfHandle
import com.geoknoesis.kastor.gen.runtime.DefaultRdfHandle
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.gen.runtime.validateAll
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read counts of graphs without a modification stamp: one read per `validateAll` batch, none for a graph found by
 * its handle under `assumeImmutable`; a changing graph behind equal handles keeps one store.
 */
class Rdf4jBatchValidationTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:minCount 1 ] .
    """.trimIndent()

    private fun people(names: List<String>): List<RdfTriple> = names.flatMap { p ->
        listOf(RdfTriple(ex(p), RDF.type, ex("Person")), RdfTriple(ex(p), ex("name"), Literal(p)))
    }

    /** A graph without a modification stamp that counts full reads. */
    private open class CountingGraph(protected val inner: RdfGraph, val reads: AtomicInteger = AtomicInteger()) : RdfGraph {
        override fun hasTriple(triple: RdfTriple) = inner.hasTriple(triple)
        override fun getTriples(): List<RdfTriple> {
            reads.incrementAndGet()
            return inner.getTriples()
        }
        override fun size() = inner.size()
    }

    /** As a repository graph handle will be: a new object per `getGraph(name)`, equal to the others of that name. */
    private class NamedHandle(val name: String, inner: RdfGraph, reads: AtomicInteger) : CountingGraph(inner, reads), HandleEqualGraph {
        override fun equals(other: Any?): Boolean = other is NamedHandle && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }

    private class Person(override val rdf: RdfHandle) : RdfBacked

    private val names = List(20) { "p$it" }
    private fun data(): MemoryGraph = MemoryGraph().apply {
        addTriples(people(names))
        removeTriple(RdfTriple(ex("p7"), ex("name"), Literal("p7")))
    }

    @Test
    fun `validateAll reads a graph without a stamp once for all the nodes`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g = CountingGraph(data())
            val results = v.validateAll(g, names.map(::ex) + ex("p0"))
            assertEquals(1, g.reads.get(), "one read for the whole batch")
            assertEquals(1, v.digestCount)
            assertEquals(names.map(::ex), results.keys.toList())
            assertEquals(listOf(ex("p7")), results.filterValues { it is ValidationResult.Violations }.keys.toList())
            assertEquals(1, v.loadCount)

            // The same loop with validate reads the graph once per node.
            g.reads.set(0)
            names.forEach { v.validate(g, ex(it)) }
            assertEquals(names.size, g.reads.get())
            assertTrue(v.validateAll(g, emptyList()).isEmpty())
            assertEquals(names.size, g.reads.get(), "an empty batch does not read the graph")
            assertThrows(IllegalArgumentException::class.java) { v.validateAll(g, listOf(Literal("x"))) }
        }
    }

    @Test
    fun `wrappers of one graph are validated in one batch`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g = CountingGraph(data())
            val other = CountingGraph(MemoryGraph().apply { addTriples(people(listOf("z"))) })
            val wrappers = names.map { Person(DefaultRdfHandle(ex(it), g, emptySet())) } +
                Person(DefaultRdfHandle(ex("z"), other, emptySet()))
            val results = v.validateAll(wrappers)
            assertEquals(1, g.reads.get(), "one read of the graph for all its wrappers")
            assertEquals(1, other.reads.get())
            assertEquals(wrappers, results.map { it.first })
            assertEquals(listOf(ex("p7")), results.filter { it.second is ValidationResult.Violations }.map { it.first.rdf.node })
        }
    }

    @Test
    fun `assumeImmutable reads a graph found by its handle once`() {
        Rdf4jValidation(com.geoknoesis.kastor.rdf.Rdf.parse(shapes, "TURTLE"), assumeImmutable = true).use { v ->
            assertTrue(v.assumeImmutable)
            assertEquals(Rdf4jValidation.DEFAULT_MAX_CACHED_GRAPHS, v.maxCachedGraphs)
            val inner = data()
            val g = CountingGraph(inner)
            names.forEach { v.validate(g, ex(it)) }
            assertEquals(1, g.reads.get(), "the same graph object is read once")

            val reads = AtomicInteger()
            val other = MemoryGraph().apply { addTriples(people(listOf("a", "b"))) }
            repeat(10) { assertEquals(ValidationResult.Ok, v.validate(NamedHandle("g", other, reads), ex("a"))) }
            assertEquals(1, reads.get(), "equal handles are read once")
            assertEquals(2, v.loadCount)

            // The caller's promise: a change of such a graph is not detected.
            other.removeTriple(RdfTriple(ex("a"), ex("name"), Literal("a")))
            assertEquals(ValidationResult.Ok, v.validate(NamedHandle("g", other, reads), ex("a")))
        }
        Rdf4jValidation().use { v -> assertEquals(false, v.assumeImmutable) }
    }

    @Test
    fun `a changing graph behind equal handles keeps one store`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val inner = MemoryGraph().apply { addTriples(people(listOf("a"))) }
            val reads = AtomicInteger()
            repeat(8) { version ->
                inner.addTriples(people(listOf("n$version")))
                assertEquals(ValidationResult.Ok, v.validate(NamedHandle("g", inner, reads), ex("n$version")))
                assertEquals(1, v.cachedGraphCount(), "the store of the previous version is replaced, not kept")
            }
            assertEquals(8, v.loadCount)
        }
    }

    @Test
    fun `an invalid cache size property falls back to the default`() {
        // That the invalid value is reported once is a rule of the shared cache, tested with it (runtime module):
        // here only what this validator does with the property.
        val previous = System.getProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY)
        try {
            for (invalid in listOf("0", "abc", "-3")) {
                System.setProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY, invalid)
                Rdf4jValidation().use { v -> assertEquals(Rdf4jValidation.DEFAULT_MAX_CACHED_GRAPHS, v.maxCachedGraphs, invalid) }
            }
            System.setProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY, " 5 ")
            Rdf4jValidation().use { v -> assertEquals(5, v.maxCachedGraphs) }
        } finally {
            if (previous == null) {
                System.clearProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY)
            } else {
                System.setProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY, previous)
            }
        }
    }

}
