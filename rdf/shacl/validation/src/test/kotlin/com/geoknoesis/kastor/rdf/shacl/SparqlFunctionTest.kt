package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** SHACL 1.2 SPARQL §7: `sh:ListParameterExpressionFunction` declarations with SPARQL bodies, called from SPARQL. */
class SparqlFunctionTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private val d = "$"

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validator(provider: String) = NativeShaclValidator(ValidationConfig.default()) { repository(provider) }

    private fun validate(provider: String, data: String, shapes: String) = validator(provider).validate(g(data), g(shapes))

    private fun w3c(name: String): String =
        File("test-data/w3c-shacl12/tests/sparql/functions/$name").readText()

    private fun w3cGraph(name: String) = Rdf.parse(w3c(name), RdfFormat.TURTLE, "http://example.com/test/$name")

    /**
     * The lexical form of the value of [expression] in a SHACL query, or null when it is unbound. (`IF(BOUND(...))`
     * rather than `COALESCE`: RDF4J leaves `COALESCE(?v, ...)` unbound after a `BIND` whose expression failed.)
     */
    private fun valueOf(provider: String, functions: String, expression: String): String? {
        val shapes = """
            $functions
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND ($expression AS ?v) BIND (IF(BOUND(?v), ?v, "unbound") AS ?value) }''' ] .
        """
        val violation = validate(provider, "ex:a ex:p 1 .", shapes).violations.single()
        return (violation.value as Literal).lexical.takeUnless { it == "unbound" }
    }

    private val spacedConcat = """
        ex:spacedConcat a sh:ListParameterExpressionFunction ;
          sh:bodyExpression [ sh:sparqlExpr "CONCAT(${d}arg0, ' ', ${d}arg1)" ] ;
          sh:parameter [ sh:path shnex:arg0 ] , [ sh:path shnex:arg1 ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `spacedConcat example`(provider: String) {
        val graph = w3cGraph("spacedConcat-example.ttl")
        val violation = validator(provider).validate(graph, graph).violations.single()
        assertEquals(ViolationSeverity.WARNING, violation.severity)
        assertEquals("John Doe", (violation.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `langLabelCount example`(provider: String) {
        val graph = w3cGraph("langLabelCount-example.ttl")
        val violation = validator(provider).validate(graph, graph).violations.single()
        assertEquals("2 2 1 false", (violation.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `argument count follows the parameter declarations`(provider: String) {
        assertEquals("a b", valueOf(provider, spacedConcat, "<http://example.com/ns#spacedConcat>('a', 'b')"))
        assertEquals(null, valueOf(provider, spacedConcat, "<http://example.com/ns#spacedConcat>('a')"))
        assertEquals(null, valueOf(provider, spacedConcat, "<http://example.com/ns#spacedConcat>('a', 'b', 'c')"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a body with zero or several results is an error`(provider: String) {
        val functions = """
            ex:several a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:select "SELECT ?x WHERE { VALUES ?x { 1 2 } }" ] .
            ex:none a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:select "SELECT ?x WHERE { FILTER (false) }" ] .
            ex:one a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:select "SELECT ?x WHERE { VALUES ?x { 1 UNDEF } }" ] .
        """
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#several>())"))
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#none>())"))
        // Rows where the projected variable is unbound do not count.
        assertEquals("1", valueOf(provider, functions, "STR(<http://example.com/ns#one>())"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `this is the function IRI and functions call each other`(provider: String) {
        val functions = """
            ex:self a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:sparqlExpr "STR(${d}this)" ] .
            ex:wrap a sh:ListParameterExpressionFunction ;
              sh:bodyExpression [ sh:sparqlExpr "CONCAT('[', <http://example.com/ns#self>(), ']')" ] .
        """
        assertEquals("[http://example.com/ns#self]", valueOf(provider, functions, "<http://example.com/ns#wrap>()"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `recursive functions end at the depth limit`(provider: String) {
        val functions = """
            ex:loop a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:sparqlExpr "<http://example.com/ns#loop>()" ] .
        """
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#loop>())"))
    }

    private fun constantFunction(value: String) = """
        ex:f a sh:ListParameterExpressionFunction ; sh:bodyExpression [ sh:sparqlExpr "'$value'" ] .
        ex:S a sh:NodeShape ; sh:targetNode ex:a ;
          sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#f>() AS ?value) }''' ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `concurrent validations see only their own functions`(provider: String) {
        val validator = validator(provider)
        val data = g("ex:a ex:p 1 .")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = listOf("one", "two").map { expected ->
                val shapes = g(constantFunction(expected))
                pool.submit(
                    Callable {
                        repeat(50) {
                            val value = (validator.validate(data, shapes).violations.single().value as Literal).lexical
                            assertEquals(expected, value)
                        }
                    },
                )
            }
            runs.forEach { it.get(5, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `with functions declared an in-place dataset is not queried`(provider: String) {
        val copies = AtomicInteger()
        val validator = NativeShaclValidator(ValidationConfig.default()) { copies.incrementAndGet(); repository(provider) }
        repository(provider).use { repo ->
            repo.transaction { editDefaultGraph().addTriples(g("ex:a ex:p 1 .").getTriples()) }
            val plain = """
                ex:S a sh:NodeShape ; sh:targetNode ex:a ;
                  sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { ${d}this <http://example.com/ns#p> ?value }''' ] .
            """
            assertEquals(1, validator.validateDataset(repo, g(plain)).violations.size)
            assertEquals(0, copies.get(), "without functions the dataset is queried in place")
            val report = validator.validateDataset(repo, g(constantFunction("one")))
            assertEquals("one", (report.violations.single().value as Literal).lexical)
            assertEquals(1, copies.get(), "with functions the private copy is queried")
        }
    }

    private inline fun <reified T : Throwable> Throwable.causeOfType(): T? =
        generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `ill-formed declarations`(provider: String) {
        val use = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#f>(1) AS ?value) }''' ] .
        """
        val body = """sh:bodyExpression [ sh:sparqlExpr "1" ]"""
        val cases = listOf(
            """[] a sh:ListParameterExpressionFunction ; $body .""",
            """ex:f a sh:ListParameterExpressionFunction ; $body , [ sh:sparqlExpr "2" ] .""",
            """ex:f a sh:ListParameterExpressionFunction ; $body ; sh:parameter [ sh:path shnex:first ] .""",
            """ex:f a sh:ListParameterExpressionFunction ; $body ; sh:parameter [ sh:path shnex:arg0 ] , [ sh:path shnex:arg2 ] .""",
            """ex:f a sh:ListParameterExpressionFunction ; $body ;
                 sh:parameter [ sh:path shnex:arg0 ; sh:optional true ] , [ sh:path shnex:arg1 ] .""",
        )
        for (declaration in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", declaration + use) }, declaration)
            assertNotNull(error.causeOfType<ShapeCompileException>(), "$declaration: $error")
        }
        val rebinding = """
            ex:f a sh:ListParameterExpressionFunction ; sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sh:select "SELECT ?x { BIND (1 AS ?arg0) }" ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", rebinding + use) }
        assertTrue(error.causeOfType<SparqlPreBindingRestrictionException>() != null, error.toString())
    }
}
