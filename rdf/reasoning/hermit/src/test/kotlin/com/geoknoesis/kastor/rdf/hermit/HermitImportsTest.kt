package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `owl:imports` in the graph handed to HermiT is never resolved: no HTTP request is made, no file is read, and
 * nothing an import would have contributed reaches the result. An import that could not be resolved anyway does
 * not fail the call either.
 */
@Timeout(60)
class HermitImportsTest {
    private val requests = CopyOnWriteArrayList<String>()

    /** An ontology that, if imported, would make `ex:i` an instance of `ex:Leaked`. */
    private val imported = """
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        <urn:kastor:test:imported> a owl:Ontology .
        <http://example.org/ns#A> a owl:Class ; rdfs:subClassOf <http://example.org/ns#Leaked> .
        <http://example.org/ns#Leaked> a owl:Class .
    """.trimIndent()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                requests.add(exchange.requestURI.path)
                val body = imported.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/turtle")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
            }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val leaked = RdfTriple(Iri("http://example.org/ns#i"), Iri(RDF_TYPE), Iri("http://example.org/ns#Leaked"))

    private fun importingTurtle(target: String) = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        <urn:kastor:test:o> a owl:Ontology ; owl:imports <$target> .
        <http://example.org/ns#A> a owl:Class .
        <http://example.org/ns#i> a owl:NamedIndividual , <http://example.org/ns#A> .
    """.trimIndent()

    private fun importing(target: String): RdfGraph = Rdf.parse(importingTurtle(target), RdfFormat.TURTLE)

    private fun assertNotImported(graph: RdfGraph) {
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit())
        val result = reasoner.reason(graph)
        assertTrue(result.consistencyCheck.isConsistent)
        assertFalse(leaked in result.inferredTriples, "an import contributed ${result.inferredTriples}")
        assertTrue(reasoner.isConsistent(graph))
        val supers = reasoner.classify(graph).classHierarchy[Iri("http://example.org/ns#A")].orEmpty()
        assertFalse(Iri("http://example.org/ns#Leaked") in supers, "an import contributed $supers")
    }

    @Test
    fun `an HTTP import is never requested`() {
        assertNotImported(importing("http://127.0.0.1:${server.address.port}/secret"))
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a file import is never read`(@TempDir dir: Path) {
        val file = dir.resolve("imported.ttl").toFile().apply { writeText(imported) }
        assertNotImported(importing(file.toURI().toString()))
    }

    @Test
    fun `an unresolvable import does not fail the call`() {
        assertNotImported(importing("http://127.0.0.1:1/unreachable"))
        assertNotImported(importing("urn:kastor:test:nowhere"))
    }

    @Test
    fun `the loader refuses every document but the input, even with imports present`(@TempDir dir: Path) {
        val file = dir.resolve("imported.ttl").toFile().apply { writeText(imported) }
        val turtle = importingTurtle(file.toURI().toString()) +
            "\n<urn:kastor:test:o> <http://www.w3.org/2002/07/owl#imports> <http://127.0.0.1:${server.address.port}/other> .\n"
        val manager = HermitOntologyLoading.isolatedManager()
        val ontology = manager.loadOntologyFromOntologyDocument(HermitOntologyLoading.inputDocument(turtle))
        assertEquals(1L, manager.ontologies().count(), "only the input document is loaded")
        assertEquals(0L, ontology.imports().count())
        assertEquals(2L, ontology.importsDeclarations().count(), "the declarations themselves are kept")
        assertEquals(emptyList<String>(), requests.toList())
    }

    private companion object {
        const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
    }
}
