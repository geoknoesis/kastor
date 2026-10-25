package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
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
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
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

    /** A W3C `sparql/functions` example, bundled with the test fixture (`w3c-shacl12-fixture/sparql/functions/`). */
    private fun w3c(name: String): String =
        checkNotNull(SparqlFunctionTest::class.java.getResource("/w3c-shacl12-fixture/sparql/functions/$name")) {
            "bundled W3C example $name missing from the test classpath"
        }.readText()

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
        ex:spacedConcat a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
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
            ex:several a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ sh:select "SELECT ?x WHERE { VALUES ?x { 1 2 } }" ] .
            ex:none a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ sh:select "SELECT ?x WHERE { FILTER (false) }" ] .
            ex:one a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ sh:select "SELECT ?x WHERE { VALUES ?x { 1 UNDEF } }" ] .
        """
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#several>())"))
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#none>())"))
        // Rows where the projected variable is unbound do not count.
        assertEquals("1", valueOf(provider, functions, "STR(<http://example.com/ns#one>())"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `this is the function IRI and functions call each other`(provider: String) {
        val functions = """
            ex:self a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ sh:sparqlExpr "STR(${d}this)" ] .
            ex:wrap a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
              sh:bodyExpression [ sh:sparqlExpr "CONCAT('[', <http://example.com/ns#self>(), ']')" ] .
        """
        assertEquals("[http://example.com/ns#self]", valueOf(provider, functions, "<http://example.com/ns#wrap>()"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `recursive functions end at the depth limit`(provider: String) {
        val functions = """
            ex:loop a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ sh:sparqlExpr "<http://example.com/ns#loop>()" ] .
        """
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#loop>())"))
    }

    private fun constantFunction(value: String) = """
        ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ sh:sparqlExpr "'$value'" ] .
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

    // --- shnex: bodies -------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `instanceCount example`(provider: String) {
        val graph = w3cGraph("instanceCount-example.ttl")
        val violation = validator(provider).validate(graph, graph).violations.single()
        assertEquals(ViolationSeverity.WARNING, violation.severity)
        assertEquals("2", (violation.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `shnex bodies called from SPARQL`(provider: String) {
        val functions = """
            ex:spaced a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
              sh:bodyExpression [ sparql:concat ( [ shnex:arg 0 ] " " [ shnex:arg 1 ] ) ] ;
              sh:parameter [ sh:path shnex:arg0 ] , [ sh:path shnex:arg1 ] .
            ex:self a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:var "focusNode" ] .
            ex:constant a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression "c" .
            ex:several a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression ( 1 2 ) .
            ex:none a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [] .
            ex:failing a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:pathValues ex:p ; shnex:focusNode ( 1 2 ) ] .
            ex:opt a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:parameter [ sh:path shnex:arg0 ; sh:optional true ] ;
              sh:bodyExpression [ shnex:count [ shnex:arg 0 ] ] .
        """
        // A shnex: body evaluates SPARQL function expressions with nested queries.
        assertEquals("a b", valueOf(provider, functions, "<http://example.com/ns#spaced>('a', 'b')"))
        // The focus node is the function IRI (SPARQL §7.3).
        assertEquals("http://example.com/ns#self", valueOf(provider, functions, "STR(<http://example.com/ns#self>())"))
        assertEquals("c", valueOf(provider, functions, "<http://example.com/ns#constant>()"))
        // Exactly one output node is the result; none, several or a failure is an error.
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#several>())"))
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#none>())"))
        assertEquals(null, valueOf(provider, functions, "STR(<http://example.com/ns#failing>())"))
        // An unbound optional argument has no output nodes.
        assertEquals("0", valueOf(provider, functions, "STR(<http://example.com/ns#opt>())"))
        assertEquals("1", valueOf(provider, functions, "STR(<http://example.com/ns#opt>(1))"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `recursion through shnex bodies ends at the depth limit`(provider: String) {
        // Each level counts the solutions of a query that calls the function again: the deepest call is an error.
        val functions = """
            ex:rec a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
              sh:bodyExpression [ shnex:count [ sh:select "SELECT ?x WHERE { BIND (<http://example.com/ns#rec>() AS ?x) }" ] ] .
        """
        assertEquals("1", valueOf(provider, functions, "STR(<http://example.com/ns#rec>())"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `with only shnex functions declared an in-place dataset is not queried`(provider: String) {
        val copies = AtomicInteger()
        val validator = NativeShaclValidator(ValidationConfig.default()) { copies.incrementAndGet(); repository(provider) }
        repository(provider).use { repo ->
            repo.transaction { editDefaultGraph().addTriples(g("ex:a ex:p 1 .").getTriples()) }
            val shapes = """
                ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:concat ( "one" ) ] .
                ex:S a sh:NodeShape ; sh:targetNode ex:a ;
                  sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#f>() AS ?value) }''' ] .
            """
            val report = validator.validateDataset(repo, g(shapes))
            assertEquals("one", (report.violations.single().value as Literal).lexical)
            assertEquals(1, copies.get(), "with functions the private copy is queried")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a function whose body is no node expression is reported when SPARQL calls it`(provider: String) {
        val shapes = """
            ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ ex:unknown 1 ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#f>() AS ?value) }''' ] .
        """
        val e = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.SHACL_FUNCTION), e.causeOfType<UnsupportedShaclFeatureException>()?.features, e.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an ill-formed shnex body is an error only when SPARQL calls the function`(provider: String) {
        val bad = "ex:bad a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] ."
        val unused = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (1 AS ?value) }''' ] .
        """
        assertEquals(1, validate(provider, "ex:a ex:p 1 .", bad + unused).violations.size)
        val used = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#bad>() AS ?value) }''' ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", bad + used) }
        assertNotNull(error.causeOfType<ShapeCompileException>(), error.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an ill-formed function reached through another body is an error whatever was compiled first`(provider: String) {
        val call = { f: String ->
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#$f>() AS ?value) }''' ] .
            """
        }
        val chain = """
            ex:B a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ ex:usesBad () ] .
            ex:usesBad a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ ex:badBody () ] .
            ex:badBody a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", chain + call("B")) }
        assertNotNull(error.causeOfType<ShapeCompileException>(), error.toString())
        // An unused function whose body calls two ill-formed functions leaves no error behind for a valid one.
        val leftover = """
            ex:A a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:concat ( [ ex:bad1 () ] [ ex:bad2 () ] ) ] .
            ex:bad1 a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .
            ex:bad2 a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -2 ; shnex:nodes () ] .
            ex:B a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:concat ( "ok" ) ] .
        """
        assertEquals("ok", (validate(provider, "ex:a ex:p 1 .", leftover + call("B")).violations.single().value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a call is a prefixed name whose prefix is the function namespace`(provider: String) {
        // ex:concat is ill-formed and unused: the built-in concat( and another namespace's x:concat( do not call it,
        // and neither does a name in a comment or a string, whatever its namespace.
        val shapes = """
            ex:concat a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''PREFIX x: <urn:x:>
                SELECT ${d}this ?value WHERE {
                  # x:concat()
                  BIND ("x:concat()" AS ?unused)
                  BIND (concat("a", "b") AS ?value) }''' ] .
        """
        assertEquals("ab", (validate(provider, "ex:a ex:p 1 .", shapes).violations.single().value as Literal).lexical)
        val commentOnly = shapes.replace("<urn:x:>", "<http://example.com/ns#>")
        assertEquals("ab", (validate(provider, "ex:a ex:p 1 .", commentOnly).violations.single().value as Literal).lexical)
        // With the function's namespace, the prefixed name is a call (and the ill-formed declaration an error), also
        // for a prefix name that is not ASCII.
        for (prefix in listOf("x", "fé")) {
            val called = commentOnly.replace("BIND (concat(", "BIND ($prefix:concat() AS ?called) BIND (concat(").replace("PREFIX x:", "PREFIX $prefix:")
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", called) }, prefix)
            assertNotNull(error.causeOfType<ShapeCompileException>(), error.toString())
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a call that codepoint escapes or a relative IRI hide is a call`(provider: String) {
        // The SPARQL text of each query, written for a Turtle ''' string (\\ is one backslash in the query).
        val ex = "PREFIX ex: <http://example.com/ns#> "
        val queries = listOf(
            ex + "SELECT ${d}this ?value WHERE { BIND ('a\\\\u0027 + ex:bad() + \\\\u0027b' AS ?value) }",
            ex + "SELECT ${d}this ?value WHERE { BIND (1 AS ?value) # c \\\\u000A FILTER (!ex:bad(${d}this))\n }",
            "SELECT ${d}this ?value WHERE { BIND (<http://example.com/ns#\\\\u0062ad>() AS ?value) }",
            ex + "SELECT ${d}this ?value WHERE { BIND (ex:\\\\u0062ad() AS ?value) }",
            "PREFIX ex: <http://example.com/ns\\\\u0023> SELECT ${d}this ?value WHERE { BIND (ex:bad() AS ?value) }",
            "BASE <http://example.com/ns> SELECT ${d}this ?value WHERE { BIND (<#bad>() AS ?value) }",
            "SELECT ${d}this ?value WHERE { BIND (<bad>() AS ?value) }",
        )
        for (query in queries) {
            val shape = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''$query''' ] ."
            // An ill-formed declaration that the query calls is an error.
            val illFormed = "ex:bad a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .\n"
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", illFormed + shape) }, query)
            assertNotNull(error.causeOfType<ShapeCompileException>(), "$query: $error")
            // A body that is no node expression is reported.
            val opaque = "ex:bad a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ ex:unknown 1 ] .\n"
            val e = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", opaque + shape) }, query)
            assertEquals(setOf(UnsupportedShaclFeature.SHACL_FUNCTION), e.causeOfType<UnsupportedShaclFeatureException>()?.features, "$query: $e")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `SPARQL and shnex bodies that call each other share one nesting limit`(provider: String) {
        // ex:n(k) returns the deepest k its chain reaches: it calls ex:s(k + 1), which calls ex:n(k + 1), and falls
        // back to k when that call is an error. Every call of either kind counts towards MAX_FUNCTION_DEPTH (16):
        // the top query calls n(0) (1), s(1) (2), n(1) (3), ..., n(7) (15), s(8) (16), and n(8) is an error.
        val functions = """
            ex:n a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sparql:coalesce (
                [ sh:select "SELECT ?x WHERE { BIND (<http://example.com/ns#s>(${d}arg0 + 1) AS ?x) }" ]
                [ shnex:arg 0 ] ) ] .
            ex:s a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sh:sparqlExpr "<http://example.com/ns#n>(${d}arg0)" ] .
        """
        assertEquals("7", valueOf(provider, functions, "STR(<http://example.com/ns#n>(0))"))
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
            """[] a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; $body .""",
            """ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; $body , [ sh:sparqlExpr "2" ] .""",
            """ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; $body ; sh:parameter [ sh:path shnex:first ] .""",
            """ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; $body ; sh:parameter [ sh:path shnex:arg0 ] , [ sh:path shnex:arg2 ] .""",
            """ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; $body ;
                 sh:parameter [ sh:path shnex:arg0 ; sh:optional true ] , [ sh:path shnex:arg1 ] .""",
        )
        for (declaration in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", declaration + use) }, declaration)
            assertNotNull(error.causeOfType<ShapeCompileException>(), "$declaration: $error")
        }
        val rebinding = """
            ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sh:select "SELECT ?x { BIND (1 AS ?arg0) }" ] .
        """
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", rebinding + use) }
        assertTrue(error.causeOfType<SparqlPreBindingRestrictionException>() != null, error.toString())
    }
}
