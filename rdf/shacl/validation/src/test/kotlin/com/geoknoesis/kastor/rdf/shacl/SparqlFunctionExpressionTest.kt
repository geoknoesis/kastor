package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlSelect
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.native.SparqlFunctionExpressions
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NodeExprOutcome
import com.geoknoesis.kastor.rdf.shacl.providers.evaluateNodeExpression
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** SHACL SPARQL function expressions (Node Expressions §5), evaluated by the provider on Jena and RDF4J. */
class SparqlFunctionExpressionTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun ex(local: String) = Iri("http://example.com/ns#$local")
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)
    private fun str(s: String) = TypedLiteral(s, XSD.string)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    /** Evaluates `ex:holder ex:expr <expr>` (plus [data]), the graph being the focus graph and the shapes graph. */
    private fun eval(
        expr: String,
        provider: String,
        data: String = "",
        repositories: () -> RdfRepository = { repository(provider) },
    ): NodeExprOutcome {
        val graph = Rdf.parse("$prefixes\nex:holder ex:expr $expr .\n$data", RdfFormat.TURTLE)
        val node = graph.find(ex("holder"), ex("expr"), null).single().obj
        return NativeShaclValidator(ValidationConfig.default(), repositories).evaluateNodeExpression(graph, node, null, emptyMap())
    }

    private fun nodes(outcome: NodeExprOutcome): List<RdfTerm> {
        assertTrue(outcome is NodeExprOutcome.Nodes, "expected output nodes, got $outcome")
        return (outcome as NodeExprOutcome.Nodes).nodes
    }

    private fun assertFailure(outcome: NodeExprOutcome) = assertTrue(outcome is NodeExprOutcome.Failure, "expected a failure, got $outcome")

    // --- rendering -------------------------------------------------------------------------------------------------

    /** Every function of the §5 table with an arity SPARQL accepts. */
    private val table = mapOf(
        "abs" to 1, "bnode" to 0, "bound" to 1, "ceil" to 1, "coalesce" to 2, "concat" to 3, "contains" to 2,
        "datatype" to 1, "day" to 1, "divide" to 2, "encode" to 1, "equals" to 2, "floor" to 1, "greater-than" to 2,
        "greater-than-or-equal" to 2, "hasLang" to 1, "hasLangdir" to 1, "hours" to 1, "if" to 3, "iri" to 1,
        "isBlank" to 1, "isIRI" to 1, "isLiteral" to 1, "isNumeric" to 1, "isTriple" to 1, "isURI" to 1, "lang" to 1,
        "langdir" to 1, "langMatches" to 2, "lcase" to 1, "less-than" to 2, "less-than-or-equal" to 2,
        "logical-and" to 2, "logical-not" to 1, "logical-or" to 2, "md5" to 1, "minutes" to 1, "month" to 1,
        "multiply" to 2, "not-equals" to 2, "now" to 0, "object" to 1, "plus" to 2, "predicate" to 1, "rand" to 0,
        "regex" to 2, "replace" to 3, "round" to 1, "sameTerm" to 2, "sameValue" to 2, "seconds" to 1, "sha1" to 1,
        "sha256" to 1, "sha384" to 1, "sha512" to 1, "str" to 1, "strafter" to 2, "strbefore" to 2, "strdt" to 2,
        "strends" to 2, "strlang" to 2, "strlangdir" to 3, "strlen" to 1, "strstarts" to 2, "struuid" to 0,
        "subject" to 1, "substr" to 2, "subtract" to 2, "timezone" to 1, "triple" to 3, "tz" to 1, "ucase" to 1,
        "unary-minus" to 1, "unary-plus" to 1, "uri" to 1, "uuid" to 0, "year" to 1,
    )

    @Test
    fun `every sparql function in the spec table renders`() {
        assertEquals(77, table.size)
        for ((name, arity) in table) assertNotNull(SparqlFunctionExpressions.render(name, arity), name)
        assertEquals("(?a0 + ?a1)", SparqlFunctionExpressions.render("plus", 2))
        assertEquals("(?a0 - ?a1)", SparqlFunctionExpressions.render("subtract", 2))
        assertEquals("(?a0 * ?a1)", SparqlFunctionExpressions.render("multiply", 2))
        assertEquals("(?a0 / ?a1)", SparqlFunctionExpressions.render("divide", 2))
        assertEquals("(?a0 = ?a1)", SparqlFunctionExpressions.render("equals", 2))
        assertEquals("(?a0 != ?a1)", SparqlFunctionExpressions.render("not-equals", 2))
        assertEquals("(?a0 < ?a1)", SparqlFunctionExpressions.render("less-than", 2))
        assertEquals("(?a0 <= ?a1)", SparqlFunctionExpressions.render("less-than-or-equal", 2))
        assertEquals("(?a0 > ?a1)", SparqlFunctionExpressions.render("greater-than", 2))
        assertEquals("(?a0 >= ?a1)", SparqlFunctionExpressions.render("greater-than-or-equal", 2))
        assertEquals("(?a0 && ?a1)", SparqlFunctionExpressions.render("logical-and", 2))
        assertEquals("(?a0 || ?a1)", SparqlFunctionExpressions.render("logical-or", 2))
        assertEquals("(!?a0)", SparqlFunctionExpressions.render("logical-not", 1))
        assertEquals("(-?a0)", SparqlFunctionExpressions.render("unary-minus", 1))
        assertEquals("(+?a0)", SparqlFunctionExpressions.render("unary-plus", 1))
        assertEquals("LCASE(?a0)", SparqlFunctionExpressions.render("lcase", 1))
        assertEquals("STRLANGDIR(?a0, ?a1, ?a2)", SparqlFunctionExpressions.render("strlangdir", 3))
        assertEquals("isURI(?a0)", SparqlFunctionExpressions.render("isURI", 1))
        assertEquals("IRI(?a0)", SparqlFunctionExpressions.render("iri", 1))
        assertEquals("URI(?a0)", SparqlFunctionExpressions.render("uri", 1))
        assertEquals("ENCODE_FOR_URI(?a0)", SparqlFunctionExpressions.render("encode", 1))
        assertEquals("NOW()", SparqlFunctionExpressions.render("now", 0))
        assertEquals(setOf("bound", "coalesce", "if", "logical-and", "logical-or"), SparqlFunctionExpressions.handlesUnbound)
    }

    @Test
    fun `optional and variadic arities follow SPARQL`() {
        for ((name, arities) in mapOf(
            "regex" to listOf(2, 3), "substr" to listOf(2, 3), "replace" to listOf(3, 4), "bnode" to listOf(0, 1),
            "concat" to listOf(0, 1, 5), "coalesce" to listOf(0, 1, 5),
        )) {
            for (arity in arities) assertNotNull(SparqlFunctionExpressions.render(name, arity), "$name/$arity")
        }
        for ((name, arity) in listOf("regex" to 1, "regex" to 4, "substr" to 4, "replace" to 2, "bnode" to 2, "abs" to 0,
            "abs" to 2, "plus" to 1, "plus" to 3, "encode" to 2, "hasLang" to 2, "now" to 1, "if" to 2, "triple" to 2)) {
            assertNull(SparqlFunctionExpressions.render(name, arity), "$name/$arity")
        }
        for (unknown in listOf("noSuchFunction", "ABS", "langmatches", "in", "exists", "")) {
            assertNull(SparqlFunctionExpressions.render(unknown, 1), unknown)
        }
    }

    @Test
    fun `unknown names and wrong arities are ill-formed`() {
        for (expr in listOf("[ sparql:noSuchFunction ( 1 ) ]", "[ sparql:abs ( 1 2 ) ]", "[ sparql:plus 1 ]", "[ sparql:now ( 1 ) ]",
            "[ shnex:count [ sparql:ABS ( 1 ) ] ]")) {
            assertThrows(ShapeCompileException::class.java, { eval(expr, "jena") }, expr)
        }
    }

    // --- evaluation ------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `functions and operators evaluate`(provider: String) {
        assertEquals(listOf(int(42)), nodes(eval("[ sparql:plus ( 38 4 ) ]", provider)))
        assertEquals(listOf(int(42)), nodes(eval("[ sparql:abs -42 ]", provider)))
        assertEquals(listOf(str("hello world")), nodes(eval("""[ sparql:concat ( "hello" " " "world" ) ]""", provider)))
        assertEquals(listOf(FalseLiteral), nodes(eval("[ sparql:logical-not ( true ) ]", provider)))
        assertEquals(listOf(int(-42)), nodes(eval("[ sparql:unary-minus ( 42 ) ]", provider)))
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:greater-than ( [ sparql:plus ( 1 2 ) ] 2 ) ]", provider)))
        assertEquals(listOf(ex("r")), nodes(eval("""[ sparql:iri ( "http://example.com/ns#r" ) ]""", provider)))
        // A SPARQL error of the function itself is an evaluation failure.
        assertFailure(eval("""[ sparql:abs ( "x" ) ]""", provider))
        assertFailure(eval("[ sparql:divide ( 1 0 ) ]", provider))
    }

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `failing argument beats empty argument in either order`(provider: String) {
        val empty = "[ shnex:limit 0 ; shnex:nodes ( 1 ) ]"
        val failing = "[ sparql:abs ( ( 1 2 ) ) ]"
        assertFailure(eval("[ sparql:concat ( $empty $failing ) ]", provider))
        assertFailure(eval("[ sparql:concat ( $failing $empty ) ]", provider))
        // The spec's own example: an unbound variable and a failing shnex:instancesOf.
        assertFailure(eval("""[ sparql:plus ( [ shnex:var "unbound" ] [ shnex:instancesOf 42 ] ) ]""", provider))
        assertFailure(eval("""[ sparql:plus ( [ shnex:instancesOf 42 ] [ shnex:var "unbound" ] ) ]""", provider))
    }

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `unbound handling`(provider: String) {
        assertEquals(listOf(str("ok")), nodes(eval("""[ sparql:coalesce ( ( 1 2 ) "ok" ) ]""", provider)))
        assertFailure(eval("[ sparql:abs ( ( -1 -2 ) ) ]", provider))
        assertEquals(listOf(FalseLiteral), nodes(eval("""[ sparql:bound ( [ shnex:var "x" ] ) ]""", provider)))
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:bound ( 1 ) ]", provider)))
        assertEquals(listOf(str("default")), nodes(eval("""[ sparql:coalesce ( [ shnex:var "x" ] "default" ) ]""", provider)))
        // A selected unbound value is an error, as in SPARQL; an unselected one is ignored.
        assertFailure(eval("""[ sparql:if ( true [ shnex:var "unbound" ] "no" ) ]""", provider))
        assertEquals(listOf(str("no")), nodes(eval("""[ sparql:if ( false [ shnex:var "unbound" ] "no" ) ]""", provider)))
        assertFailure(eval("""[ sparql:coalesce ( [ shnex:var "x" ] [ shnex:var "y" ] ) ]""", provider))
        assertEquals(listOf(TrueLiteral), nodes(eval("""[ sparql:logical-or ( [ shnex:var "x" ] true ) ]""", provider)))
        assertEquals(listOf(FalseLiteral), nodes(eval("""[ sparql:logical-and ( [ shnex:var "x" ] false ) ]""", provider)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `an errored argument reaches SPARQL as an error, not as unbound`(provider: String) {
        val error = "( 1 2 )"
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:logical-or ( true $error ) ]", provider)))
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:logical-or ( $error true ) ]", provider)))
        assertFailure(eval("[ sparql:logical-or ( false $error ) ]", provider))
        assertEquals(listOf(FalseLiteral), nodes(eval("[ sparql:logical-and ( $error false ) ]", provider)))
        assertEquals(listOf(str("a")), nodes(eval("""[ sparql:if ( true "a" $error ) ]""", provider)))
        assertFailure(eval("""[ sparql:if ( false "a" $error ) ]""", provider))
        assertFailure(eval("""[ sparql:if ( $error "a" "b" ) ]""", provider))
        // BOUND of an error is an error, not false.
        assertFailure(eval("[ sparql:bound ( $error ) ]", provider))
        // Both unbound and errored arguments: COALESCE skips both.
        assertEquals(listOf(int(3)), nodes(eval("""[ sparql:coalesce ( [ shnex:var "x" ] $error [ sparql:abs ( "x" ) ] 3 ) ]""", provider)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `empty argument means no output`(provider: String) {
        assertEquals(emptyList<RdfTerm>(), nodes(eval("[ sparql:abs ( [] ) ]", provider)))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("[ sparql:abs [ shnex:limit 0 ; shnex:nodes ( 1 ) ] ]", provider)))
        assertEquals(emptyList<RdfTerm>(), nodes(eval("""[ sparql:concat ( "a" [ shnex:var "x" ] ) ]""", provider)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `blank node arguments and results`(provider: String) {
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:isBlank ( [ sparql:bnode () ] ) ]", provider)))
        val data = "ex:a ex:p [ ex:q 1 ] ."
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:isBlank ( [ shnex:pathValues ex:p ; shnex:focusNode ex:a ] ) ]", provider, data)))
        assertEquals(
            listOf(TrueLiteral),
            nodes(eval("[ sparql:sameTerm ( [ shnex:pathValues ex:p ; shnex:focusNode ex:a ] [ shnex:pathValues ex:p ; shnex:focusNode ex:a ] ) ]", provider, data)),
        )
        val bnode = nodes(eval("[ sparql:bnode () ]", provider)).single()
        assertTrue(bnode is com.geoknoesis.kastor.rdf.BlankNode, "$bnode")
    }

    @Test
    fun `triple term and directional arguments on jena`() {
        assertEquals(listOf(TrueLiteral), nodes(eval("[ sparql:isTriple ( <<( ex:s ex:p ex:o )>> ) ]", "jena")))
        assertEquals(listOf(ex("o")), nodes(eval("[ sparql:object ( <<( ex:s ex:p ex:o )>> ) ]", "jena")))
        assertEquals(listOf(str("ltr")), nodes(eval("""[ sparql:langdir ( "hello"@en--ltr ) ]""", "jena")))
        assertEquals(listOf(str("hello")), nodes(eval("""[ sparql:str ( "hello"@en--ltr ) ]""", "jena")))
    }

    // --- sh:select / sh:sparqlExpr inside function arguments ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SPARQL error of a select expression is a failure that coalesce recovers from`(provider: String) {
        val broken = """[ sh:select "SELECT ?x WHERE { ?x ?p }" ]"""
        assertFailure(eval(broken, provider))
        assertEquals(listOf(str("ok")), nodes(eval("""[ sparql:coalesce ( $broken "ok" ) ]""", provider)))
    }

    // --- run aborts --------------------------------------------------------------------------------------------------

    /** A repository whose bound queries fail as cancelled by the provider ([cause]). */
    private fun cancelling(provider: String, cause: Throwable): () -> RdfRepository = {
        val delegate = repository(provider)
        object : RdfRepository by delegate {
            override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: Duration, consume: (Sequence<BindingSet>) -> T): T =
                throw RdfQueryException("query cancelled", query = query.sparql, cause = cause)
        }
    }

    @Test
    fun `provider cancellations propagate instead of becoming failures`() {
        val causes = mapOf(
            "jena" to org.apache.jena.query.QueryCancelledException(),
            "rdf4j" to org.eclipse.rdf4j.query.QueryInterruptedException("interrupted"),
        )
        for ((provider, cause) in causes) {
            for (expr in listOf("[ sparql:abs ( -1 ) ]", "[ shnex:sum ( 1 2 ) ]", """[ sh:select "SELECT ?x WHERE { BIND (1 AS ?x) }" ]""",
                """[ sparql:coalesce ( [ sh:select "SELECT ?x WHERE { BIND (1 AS ?x) }" ] 2 ) ]""")) {
                assertThrows(ShaclValidationException::class.java, { eval(expr, provider, repositories = cancelling(provider, cause)) }, "$provider $expr")
            }
            // A cancelled query of a shape-based function's conformance check is a run abort as well.
            val shape = """ex:S sh:sparql [ sh:select "SELECT ${'$'}this WHERE { FILTER (false) }" ] ."""
            assertThrows(ShaclValidationException::class.java, {
                eval("[ shnex:filterShape ex:S ; shnex:nodes ( ex:a ) ]", provider, data = shape, repositories = cancelling(provider, cause))
            }, "$provider filterShape")
        }
    }
}
