package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shapes embedded in the data graph are untrusted input: the no-arg validator refuses those that `jena-shacl` would
 * run without a bound (SHACL-SPARQL, with `SERVICE` reaching any host and no deadline; `sh:pattern` on
 * `java.util.regex`) before the engine runs, so a hostile data graph can neither make it send a request nor hang it.
 */
@Timeout(60)
class JenaEmbeddedShapesAdmissionTest {
    /**
     * The SERVICE endpoint: records the remote port of each connection and closes it at once (an endpoint that never
     * answered would hang a validation that reached it, as the engines have no deadline).
     */
    private val server = ServerSocket(0)
    private val connections = CopyOnWriteArrayList<Int>()
    private val acceptor = Thread {
        try {
            while (true) server.accept().use { connections.add(it.port) }
        } catch (_: java.io.IOException) {
        }
    }.apply { isDaemon = true; start() }

    @AfterEach
    fun stop() {
        server.close()
        acceptor.join(5_000)
    }

    /**
     * Connects once and waits until the server accepted that connection: connections are accepted in the order they
     * were made, so when it is the first one the server saw, the validations before it made none.
     */
    private fun assertNoConnectionBefore() {
        java.net.Socket("127.0.0.1", server.localPort).use { marker ->
            while (connections.isEmpty()) Thread.onSpinWait()
            assertEquals(marker.localPort, connections.first(), "the validator connected to the SERVICE endpoint")
        }
    }

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://ex/> .\n"
    private val focus = Iri("http://ex/a")
    private val serviceShape = prefixes +
        "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select \"SELECT \$this WHERE { SERVICE <http://127.0.0.1:${server.localPort}/sparql> { ?s ?p ?o } }\" ] .\n" +
        "ex:a ex:p \"x\" .\n"
    private val sparqlTarget = prefixes +
        "ex:S a sh:NodeShape ; sh:target [ a sh:SPARQLTarget ; sh:select \"SELECT ?this WHERE { SERVICE <http://127.0.0.1:${server.localPort}/> { ?this ?p ?o } }\" ] ; sh:minCount 1 .\n" +
        "ex:a ex:p \"x\" .\n"
    private val patternShape = prefixes +
        "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:pattern \"^(.*a){20}\$\" ] .\n" +
        "ex:a ex:p \"x\" .\n"

    /** The data graph as a standalone Jena graph (validated in place) and as a graph that is copied and cached. */
    private fun graphs(turtle: String): List<RdfGraph> =
        listOf(JenaBridge.fromString(turtle, "TURTLE"), MemoryGraph(Rdf.parse(turtle, "TURTLE").getTriples()))

    @Test
    fun `embedded SHACL-SPARQL is refused before any request is sent`() {
        for (turtle in listOf(serviceShape, sparqlTarget)) {
            for (graph in graphs(turtle)) {
                val error = assertThrows(EmbeddedShapesRefusedException::class.java) { JenaValidation().validate(graph, focus) }
                assertTrue(error.message!!.contains("http://www.w3.org/ns/shacl#select"), error.message)
                assertTrue(error.message!!.contains("trustingEmbeddedShapes"), error.message)
                assertThrows(EmbeddedShapesRefusedException::class.java) { JenaValidation().validateAll(graph, listOf(focus)) }
            }
        }
        assertNoConnectionBefore()
    }

    @Test
    fun `an embedded sh pattern is refused, the pattern never runs`() {
        for (graph in graphs(patternShape)) {
            val error = assertThrows(EmbeddedShapesRefusedException::class.java) { JenaValidation().validate(graph, focus) }
            assertTrue(error.message!!.contains("http://www.w3.org/ns/shacl#pattern"), error.message)
        }
    }

    @Test
    fun `SHACL Core shapes and a stored validation report are admitted`() {
        val turtle = prefixes +
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:q ; sh:minCount 1 ; sh:name \"q\" ] .\n" +
            "ex:a ex:p \"x\" .\n" +
            "[] a sh:ValidationReport ; sh:conforms false ; sh:result [ sh:focusNode ex:a ; sh:resultSeverity sh:Violation ] .\n"
        for (graph in graphs(turtle)) {
            val result = JenaValidation().validate(graph, focus)
            assertTrue(result is ValidationResult.Violations, result.toString())
        }
    }

    @Test
    fun `a trusting validator runs every embedded shape`() {
        val turtle = prefixes + "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:pattern \"^y\" ] .\nex:a ex:p \"x\" .\n"
        for (graph in graphs(turtle)) {
            assertTrue(JenaValidation.trustingEmbeddedShapes().validate(graph, focus) is ValidationResult.Violations)
        }
    }

    @Test
    fun `shapes passed to the constructor are trusted`() {
        val shapes = Rdf.parse(prefixes + "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:pattern \"^y\" ] .\n", "TURTLE")
        val data = Rdf.parse(prefixes + "ex:a ex:p \"x\" .\n", "TURTLE")
        assertTrue(JenaValidation(shapes).validate(data, focus) is ValidationResult.Violations)
    }
}
