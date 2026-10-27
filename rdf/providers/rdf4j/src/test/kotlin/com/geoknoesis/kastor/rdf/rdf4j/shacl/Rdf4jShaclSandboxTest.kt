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

    /**
     * `ShaclSail` evaluates `sh:pattern` either as SPARQL `REGEX` (through Kastor's sandbox, on the linear-time engine)
     * or, in its transactional plans, with its own `PatternFilter` on java.util.regex. A back-reference, which the
     * linear-time engine refuses, tells the two apart: every structure must either reach the sandbox or be refused
     * before ShaclSail runs (`sh:or`, `sh:not`, `sh:qualifiedValueShape` took the java.util.regex plan when probed).
     */
    @Test
    fun `sh pattern reaches the linear-time engine or is refused, in every shape structure`() {
        val pattern = "sh:pattern '^(a)\\\\1$'"
        val sandboxed = listOf(
            "sh:property [ sh:path ex:p ; $pattern ]",
            "sh:property [ sh:path ex:p ; $pattern ; sh:maxCount 3 ; sh:datatype xsd:string ]",
            "sh:property [ sh:path ex:p ; sh:node [ $pattern ] ]",
            "sh:property [ sh:path ex:p ; $pattern ; sh:uniqueLang true ]",
            "sh:closed true ; sh:ignoredProperties ( ex:p ) ; sh:property [ sh:path ex:p ; $pattern ]",
        )
        val refused = listOf(
            "sh:property [ sh:path ex:p ; sh:or ( [ $pattern ] [ sh:minLength 9 ] ) ]",
            "sh:property [ sh:path ex:p ; sh:and ( [ $pattern ] [ sh:minLength 1 ] ) ]",
            "sh:property [ sh:path ex:p ; sh:not [ $pattern ] ]",
            "sh:property [ sh:path ex:p ; sh:qualifiedValueShape [ $pattern ] ; sh:qualifiedMinCount 1 ]",
            "sh:or ( [ sh:node ex:Inner ] [ sh:minLength 9 ] ) . ex:Inner sh:property [ sh:path ex:p ; $pattern ]",
        )
        val textData = Rdf.parse("$prefixes\nex:a ex:p 'aa' , 'ab' .", "TURTLE")
        for ((structures, expected) in listOf(sandboxed to "back-reference", refused to "java.util.regex")) {
            for (structure in structures) {
                val shapes = graph("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\nex:S a sh:NodeShape ; sh:targetNode ex:a ; $structure .")
                val outcome = runCatching { validator().validate(textData, shapes) }
                val error = outcome.exceptionOrNull()
                assertTrue(
                    error is ShaclValidationException && generateSequence<Throwable>(error) { it.cause }.any { expected in it.message.orEmpty() },
                    "$structure: expected \"$expected\", got ${error ?: outcome.getOrNull()?.violations}",
                )
            }
        }
    }

    /**
     * A SHACL list may be written with IRI nodes. Round fifteen's walk followed blank nodes only, so with IRI list nodes
     * a pattern under `sh:or` reached ShaclSail's java.util.regex plan (a back-reference matched; a nested quantifier
     * took 36 s on 29 characters). Whatever the list nodes, nesting and references, the pattern is refused.
     */
    @Test
    fun `sh pattern is refused under every logical constraint whatever the list nodes are`() {
        val pattern = "sh:pattern '^(a)\\\\1$'"
        val lists = mapOf(
            "blank" to { members: List<String>, name: String -> "( ${members.joinToString(" ")} )" to "" },
            "iri" to { members: List<String>, name: String ->
                val nodes = members.indices.map { "ex:$name$it" }
                val triples = members.indices.joinToString(" ") { k ->
                    "${nodes[k]} rdf:first ${members[k]} ; rdf:rest ${if (k + 1 < nodes.size) nodes[k + 1] else "rdf:nil"} ."
                }
                nodes[0] to triples
            },
            // IRI list nodes that say more than rdf:first and rdf:rest are not renamed: the search itself must follow them.
            "typed iri" to { members: List<String>, name: String ->
                val nodes = members.indices.map { "ex:$name$it" }
                val triples = members.indices.joinToString(" ") { k ->
                    "${nodes[k]} a rdf:List ; rdf:first ${members[k]} ; rdf:rest ${if (k + 1 < nodes.size) nodes[k + 1] else "rdf:nil"} ."
                }
                nodes[0] to triples
            },
        )
        val textData = Rdf.parse("$prefixes\nex:a ex:p 'aa' , 'ab' .", "TURTLE")
        for ((kind, list) in lists) {
            for (operator in listOf("sh:or", "sh:and", "sh:xone")) {
                val (direct, directTriples) = list(listOf("[ $pattern ]", "[ sh:minLength 9 ]"), "D")
                // Nested: the pattern is inside an sh:and list inside the operator's list, reached through a named shape.
                val (inner, innerTriples) = list(listOf("ex:Named", "[ sh:minLength 1 ]"), "I")
                val (outer, outerTriples) = list(listOf("[ sh:and $inner ]", "[ sh:minLength 9 ]"), "O")
                for ((structure, extra) in listOf(
                    "sh:property [ sh:path ex:p ; $operator $direct ]" to directTriples,
                    "sh:property [ sh:path ex:p ; $operator $outer ] . ex:Named $pattern" to "$outerTriples $innerTriples",
                    "$operator $outer . ex:Named sh:property [ sh:path ex:p ; $pattern ]" to "$outerTriples $innerTriples",
                )) {
                    val shapes = graph(
                        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n" +
                            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; $structure .\n$extra",
                    )
                    val error = runCatching { validator().validate(textData, shapes) }.exceptionOrNull()
                    assertTrue(
                        error is ShaclValidationException && "java.util.regex" in error.message.orEmpty(),
                        "$kind $operator: $structure $extra: expected a refusal, got ${error ?: "a report"}",
                    )
                }
            }
        }
    }

    /** RDF4J's report of an `sh:or` whose list nodes are IRIs failed with "Illegal blank node ID character". */
    @Test
    fun `a violated sh or whose list nodes are IRIs gives a report`() {
        val shapes = graph(
            """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:or ex:L1 ] .
            ex:L1 rdf:first [ sh:minLength 99 ] ; rdf:rest ex:L2 .
            ex:L2 rdf:first [ sh:maxLength 1 ] ; rdf:rest rdf:nil .
            """.trimIndent(),
        )
        val textData = Rdf.parse("$prefixes\nex:a ex:p 'abc' .", "TURTLE")
        val report = validator().validate(textData, shapes)
        assertFalse(report.isValid)
        assertEquals(1, report.violations.size, report.violations.toString())
        assertTrue(validator().validate(Rdf.parse("$prefixes\nex:a ex:p 'a' .", "TURTLE"), shapes).isValid)
    }

    @Test
    fun `a catastrophic regular expression in a shapes query runs in linear time`() {
        // Exponential on java.util.regex; the linear-time engine decides it at once, and it does not match.
        val text = "a".repeat(10_000) + "!"
        val shapes = constraint("SELECT \$this WHERE { \$this <http://example.org/p> ?v FILTER (REGEX('$text', '((a+)+)+$')) }")
        assertTrue(validator().validate(data, shapes).isValid)
    }

    @Test
    fun `a regular expression the linear-time engine cannot honour fails the validation`() {
        val shapes = constraint("SELECT \$this WHERE { \$this <http://example.org/p> ?v FILTER (!REGEX('aa', '(a)\\\\\\\\1')) }")
        val error = assertFailsWith<ShaclValidationException> { validator().validate(data, shapes) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("back-reference") }, error.toString())
    }

    @Test
    fun `an ordinary shapes query is still evaluated`() {
        val shapes = constraint("SELECT \$this ?value WHERE { \$this <http://example.org/p> ?value }")
        assertFalse(validator().validate(data, shapes).isValid)
    }
}
