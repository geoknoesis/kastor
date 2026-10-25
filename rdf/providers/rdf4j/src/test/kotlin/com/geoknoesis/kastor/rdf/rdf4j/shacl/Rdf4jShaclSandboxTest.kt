package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SPARQL of an untrusted shapes graph (`sh:sparql` constraints, `sh:SPARQLTarget`s) that RDF4J's `ShaclSail` runs
 * is held to the same sandbox as a `SandboxedSelectQuery`: `SERVICE` contacts nothing, a function another library
 * registered in RDF4J's global registry does not run, and its expressions are bounded, so a catastrophic regular
 * expression ends instead of holding the validation worker. A refused or bounded query fails the validation; it
 * never makes the data conform.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class Rdf4jShaclSandboxTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun graph(body: String) = Rdf.parse(prefixes + "\n" + body, "TURTLE")

    private val data = graph("ex:a ex:p 1 .")

    private fun validator() = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j"))

    private fun constraint(select: String) = graph(
        """
        ex:S a sh:NodeShape ; sh:targetNode ex:a ;
          sh:sparql [ a sh:SPARQLConstraint ; sh:message "m" ; sh:select "$select" ] .
        """.trimIndent(),
    )

    private val global = "urn:kastor:test:shacl-global:" + java.util.UUID.randomUUID()
    private val calls = AtomicInteger()
    private val counting = object : Function {
        override fun getURI() = global
        @Deprecated("RDF4J's own deprecation")
        override fun evaluate(valueFactory: ValueFactory, vararg args: Value): Value {
            calls.incrementAndGet()
            return valueFactory.createLiteral(true)
        }
    }

    @AfterEach fun unregister() {
        FunctionRegistry.getInstance().remove(counting)
    }

    @Test
    fun `SERVICE in a shapes query contacts nothing`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val contacts = AtomicInteger()
            val acceptor = Thread {
                while (!server.isClosed) {
                    try { server.accept().close(); contacts.incrementAndGet() } catch (_: java.io.IOException) { }
                }
            }.apply { isDaemon = true; start() }
            val service = "SERVICE <http://127.0.0.1:${server.localPort}/sparql> { ?s ?p ?o }"
            val constraintShapes = constraint("SELECT \$this WHERE { \$this <http://example.org/p> ?v $service }")
            val targetShapes = graph(
                """
                ex:S a sh:NodeShape ;
                  sh:target [ a sh:SPARQLTarget ; sh:select "SELECT ?this WHERE { $service BIND (?s AS ?this) }" ] ;
                  sh:property [ sh:path ex:q ; sh:minCount 1 ] .
                """.trimIndent(),
            )
            for (shapes in listOf(constraintShapes, targetShapes)) {
                assertFailsWith<ShaclValidationException> { validator().validate(data, shapes) }
            }
            server.close()
            acceptor.join(10_000)
            assertEquals(0, contacts.get(), "a shapes query contacted the SERVICE endpoint")
        }
    }

    @Test
    fun `a globally registered function does not run in a shapes query`() {
        FunctionRegistry.getInstance().add(counting)
        val shapes = constraint("SELECT \$this WHERE { \$this <http://example.org/p> ?v FILTER (<$global>(?v)) }")
        runCatching { validator().validate(data, shapes) }
        assertEquals(0, calls.get(), "the global function ran inside a shapes query")
    }

    @Test
    fun `a catastrophic regular expression in a shapes query fails the validation instead of hanging`() {
        val text = "a".repeat(40) + "!"
        val shapes = constraint("SELECT \$this WHERE { \$this <http://example.org/p> ?v FILTER (REGEX('$text', '((a+)+)+$')) }")
        val error = assertFailsWith<ShaclValidationException> { validator().validate(data, shapes) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("regular expression") }, error.toString())
    }

    @Test
    fun `an ordinary shapes query is still evaluated`() {
        val shapes = constraint("SELECT \$this ?value WHERE { \$this <http://example.org/p> ?value }")
        assertFalse(validator().validate(data, shapes).isValid)
    }
}
