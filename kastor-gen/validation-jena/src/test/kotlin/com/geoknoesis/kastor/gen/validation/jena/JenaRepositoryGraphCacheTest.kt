package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.GraphStateCache
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Graphs of a Jena repository go through the shared cache (one copy and one shapes parse per content version, not
 * per call); only standalone Jena graphs, which need no copy, are validated in place. Batches read a graph once.
 */
class JenaRepositoryGraphCacheTest {

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

    /** A graph without a modification stamp that counts full reads. */
    private class CountingGraph(private val inner: RdfGraph, val reads: AtomicInteger = AtomicInteger()) : RdfGraph {
        override fun hasTriple(triple: RdfTriple) = inner.hasTriple(triple)
        override fun getTriples(): List<RdfTriple> {
            reads.incrementAndGet()
            return inner.getTriples()
        }
        override fun size() = inner.size()
    }

    @Test
    fun `a named graph of a Jena repository is copied once for many nodes and again after a change`() {
        val repo = JenaRepository.MemoryRepository()
        try {
            val name = ex("people")
            val names = Array(30) { "p$it" }
            repo.editGraph(name).addTriples(people(*names))
            JenaValidation.fromTurtle(shapes).use { v ->
                // A new handle for every node, as application code that calls repository.getGraph(name) does.
                names.forEach { assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex(it))) }
                assertEquals(1, v.loadCount, "the repository graph is copied once, not once per node")
                assertEquals(1, v.cachedGraphCount())

                repo.editGraph(name).removeTriple(RdfTriple(ex("p3"), ex("name"), Literal("p3")))
                assertTrue(v.validate(repo.getGraph(name), ex("p3")) is ValidationResult.Violations, "the change is detected")
                assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("p4")))
                assertEquals(2, v.loadCount)
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun `shapes embedded in a Jena repository graph are parsed once per content version`() {
        val repo = JenaRepository.MemoryRepository()
        try {
            repo.editDefaultGraph().addTriples(Rdf.parse(shapes, "TURTLE").getTriples())
            repo.editDefaultGraph().addTriples(people("a", "b", "c"))
            repo.editDefaultGraph().addTriple(RdfTriple(ex("d"), RDF.type, ex("Person")))
            JenaValidation().use { v ->
                val before = JenaValidation.shapeParseCount
                repeat(5) {
                    listOf("a", "b", "c").forEach { assertEquals(ValidationResult.Ok, v.validate(repo.defaultGraph, ex(it))) }
                    assertTrue(v.validate(repo.defaultGraph, ex("d")) is ValidationResult.Violations)
                }
                assertEquals(1, JenaValidation.shapeParseCount - before, "the embedded shapes are parsed once, not on every call")
                assertEquals(1, v.loadCount)
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a standalone Jena graph needs no copy and is validated in place`() {
        JenaValidation.fromTurtle(shapes).use { v ->
            val g = JenaBridge.createEmptyModel()
            g.addTriples(people("a"))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("a")))
            g.removeTriple(RdfTriple(ex("a"), ex("name"), Literal("a")))
            assertTrue(v.validate(g, ex("a")) is ValidationResult.Violations)
            assertEquals(0, v.loadCount, "nothing is copied")
            assertEquals(0, v.cachedGraphCount())
        }
    }

    @Test
    fun `validateAll reads a graph without a stamp once for all the nodes`() {
        JenaValidation.fromTurtle(shapes).use { v ->
            val names = List(20) { "p$it" }
            val inner = MemoryGraph().apply {
                addTriples(people(*names.toTypedArray()))
                removeTriple(RdfTriple(ex("p7"), ex("name"), Literal("p7")))
            }
            val g = CountingGraph(inner)
            val results = v.validateAll(g, names.map(::ex) + ex("p0"))
            assertEquals(1, g.reads.get(), "one read for the whole batch")
            assertEquals(names.map(::ex), results.keys.toList())
            assertEquals(listOf(ex("p7")), results.filterValues { it is ValidationResult.Violations }.keys.toList())
            assertEquals(1, v.loadCount)

            // The same loop with validate reads the graph once per node.
            g.reads.set(0)
            names.forEach { v.validate(g, ex(it)) }
            assertEquals(names.size, g.reads.get())
            assertTrue(v.validateAll(g, emptyList()).isEmpty())
            assertEquals(names.size, g.reads.get(), "an empty batch does not read the graph")
        }
    }

    @Test
    fun `assumeImmutable reads a graph found by its handle once`() {
        JenaValidation(Rdf.parse(shapes, "TURTLE"), assumeImmutable = true).use { v ->
            assertTrue(v.assumeImmutable)
            val names = List(10) { "p$it" }
            val inner = MemoryGraph().apply { addTriples(people(*names.toTypedArray())) }
            val g = CountingGraph(inner)
            names.forEach { assertEquals(ValidationResult.Ok, v.validate(g, ex(it))) }
            assertEquals(1, g.reads.get(), "the same graph object is read once")
            // The caller's promise: a change of such a graph is not detected.
            inner.removeTriple(RdfTriple(ex("p3"), ex("name"), Literal("p3")))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("p3")))
            assertEquals(1, v.loadCount)
        }
        JenaValidation().use { v -> assertEquals(false, v.assumeImmutable) }
    }

    @Test
    fun `an invalid cache size property is reported and the default is used`() {
        val records = ArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                synchronized(records) { records += record }
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger(GraphStateCache::class.java.name)
        logger.addHandler(handler)
        val previous = System.getProperty(JenaValidation.MAX_CACHED_GRAPHS_PROPERTY)
        try {
            for (invalid in listOf("0", "abc", "-3")) {
                System.setProperty(JenaValidation.MAX_CACHED_GRAPHS_PROPERTY, invalid)
                repeat(2) { JenaValidation().use { v -> assertEquals(JenaValidation.DEFAULT_MAX_CACHED_GRAPHS, v.maxCachedGraphs) } }
                val warnings = synchronized(records) {
                    records.filter { it.level == Level.WARNING && it.message.contains("=\"$invalid\"") }
                }
                assertEquals(1, warnings.size, "one warning for $invalid, however many validators are created")
                assertTrue(warnings.single().message.contains(JenaValidation.MAX_CACHED_GRAPHS_PROPERTY), warnings.single().message)
            }
            System.setProperty(JenaValidation.MAX_CACHED_GRAPHS_PROPERTY, " 5 ")
            JenaValidation().use { v -> assertEquals(5, v.maxCachedGraphs) }
        } finally {
            logger.removeHandler(handler)
            if (previous == null) {
                System.clearProperty(JenaValidation.MAX_CACHED_GRAPHS_PROPERTY)
            } else {
                System.setProperty(JenaValidation.MAX_CACHED_GRAPHS_PROPERTY, previous)
            }
        }
    }
}
