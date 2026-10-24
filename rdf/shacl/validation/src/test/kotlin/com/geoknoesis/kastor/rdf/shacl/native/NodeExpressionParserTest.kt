package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File

/** Parsing of SHACL 1.2 node expressions (Node Expressions §3–§6, Appendix A) into the [NodeExpression] tree. */
class NodeExpressionParserTest {
    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    private fun int(value: Int) = NodeExpression.Constant(TypedLiteral(value.toString(), XSD.integer))

    private val header = """
        @prefix ex: <http://example.com/ns#> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
    """.trimIndent()

    /** Compiles the single object of `ex:holder ex:expr ...`. */
    private fun expression(ttl: String): NodeExpression? {
        val index = ShapeGraphIndex(Rdf.parse(header + "\n" + ttl, RdfFormat.TURTLE).getTriples())
        return NodeExpressions.compile(index.objects(ex("holder"), ex("expr")).single(), index)
    }

    @Test
    fun `every W3C nodeExpr parses`() {
        val root = File("test-data/w3c-shacl12/tests/node-expr")
        Assumptions.assumeTrue(root.isDirectory, "W3C SHACL 1.2 test data absent (see test-data/README.md)")
        val nodeExpr = Iri("http://www.w3.org/ns/shacl-test#nodeExpr")
        val failures = mutableListOf<String>()
        var cases = 0
        // sparql:hasLang ( "hello"@en "en" ): the §5 table and SPARQL 1.2 HASLANG take one argument.
        val illFormed = setOf("shnex-sparql/hasLang.ttl")
        for (dir in listOf("shnex", "shnex-sparql")) {
            val files = File(root, dir).listFiles { f -> f.name.endsWith(".ttl") && f.name != "manifest.ttl" }.orEmpty().sortedBy { it.name }
            for (file in files) {
                val triples = Rdf.parse(file.readText(), RdfFormat.TURTLE, "http://example.com/test/$dir/${file.name}").getTriples()
                val index = ShapeGraphIndex(triples)
                for (t in triples.filter { it.predicate == nodeExpr }) {
                    cases++
                    val outcome = runCatching { NodeExpressions.compile(t.obj, index) }
                    if ("$dir/${file.name}" in illFormed) {
                        if (outcome.exceptionOrNull() !is ShapeCompileException) failures += "$dir/${file.name}: expected ill-formed, got $outcome"
                    } else if (outcome.getOrNull() == null) {
                        failures += "$dir/${file.name}: ${outcome.exceptionOrNull() ?: "null"}"
                    }
                }
            }
        }
        assertEquals(143, cases, "number of W3C sht:nodeExpr cases")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `builtin functions parse into their tree nodes`() {
        assertEquals(NodeExpression.Empty, expression("ex:holder ex:expr [] ."))
        assertEquals(NodeExpression.Var("focusNode"), expression("""ex:holder ex:expr [ shnex:var "focusNode" ] ."""))
        assertEquals(NodeExpression.ListExpr(listOf(int(1), int(2))), expression("ex:holder ex:expr ( 1 2 ) ."))
        assertEquals(NodeExpression.Constant(Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")), expression("ex:holder ex:expr () ."))
        assertEquals(
            NodeExpression.PathValues(ShaclPath.Inverse(ShaclPath.Predicate(ex("p"))), NodeExpression.Constant(ex("a"))),
            expression("ex:holder ex:expr [ shnex:pathValues [ sh:inversePath ex:p ] ; shnex:focusNode ex:a ] ."),
        )
        assertEquals(
            NodeExpression.If(NodeExpression.Exists(NodeExpression.Empty), int(1), null),
            expression("ex:holder ex:expr [ shnex:if [ shnex:exists [] ] ; shnex:then 1 ] ."),
        )
        assertEquals(
            NodeExpression.OrderBy(NodeExpression.Var("x"), NodeExpression.Empty, desc = true),
            expression("""ex:holder ex:expr [ shnex:orderBy [ shnex:var "x" ] ; shnex:nodes [] ; shnex:desc true ] ."""),
        )
        assertEquals(NodeExpression.Limit(2, NodeExpression.Empty), expression("ex:holder ex:expr [ shnex:limit 2 ; shnex:nodes [] ] ."))
        assertEquals(NodeExpression.FindFirst(ex("S"), null), expression("ex:holder ex:expr [ shnex:findFirst ex:S ] ."))
        assertEquals(
            NodeExpression.Intersection(listOf(NodeExpression.InstancesOf(NodeExpression.Constant(ex("A"))), NodeExpression.Empty)),
            expression("ex:holder ex:expr [ shnex:intersection ( [ shnex:instancesOf ex:A ] [] ) ] ."),
        )
        assertEquals(
            NodeExpression.ConformsToShape(NodeExpression.Var("focusNode"), NodeExpression.Constant(ex("S"))),
            expression("""ex:holder ex:expr [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:S ) ] ."""),
        )
        assertEquals(NodeExpression.Arg(0), expression("ex:holder ex:expr [ shnex:arg 0 ] ."))
        assertEquals(NodeExpression.NamedArg(ex("p")), expression("ex:holder ex:expr [ shnex:arg ex:p ] ."))
    }

    @Test
    fun `list parameter syntax rules (§3-2-2)`() {
        val bare = expression("ex:holder ex:expr [ sparql:abs -42 ] .")
        assertEquals(NodeExpression.SparqlCall("abs", listOf(int(-42))), bare)
        assertEquals(bare, expression("ex:holder ex:expr [ sparql:abs ( -42 ) ] ."))
        assertEquals(NodeExpression.SparqlCall("now", emptyList()), expression("ex:holder ex:expr [ sparql:now () ] ."))
        val f = "ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:arg 0 ] ."
        assertEquals(
            NodeExpression.CustomList(ex("f"), listOf(NodeExpression.ListExpr(listOf(int(1), int(2))))),
            expression("$f ex:holder ex:expr [ ex:f ( ( 1 2 ) ) ] ."),
        )
        assertEquals(
            NodeExpression.SparqlCall("greater-than", listOf(int(10), int(5))),
            expression("ex:holder ex:expr [ sparql:greater-than ( 10 5 ) ] ."),
        )
    }

    @Test
    fun `ill-formed expressions are compile errors`() {
        val cases = listOf(
            // §4: none of the table's properties may be used more than once.
            "ex:holder ex:expr [ shnex:if true , false ; shnex:then 1 ] .",
            // LimitExpression: sh:datatype xsd:integer, sh:minInclusive 0.
            "ex:holder ex:expr [ shnex:limit -1 ; shnex:nodes [] ] .",
            """ex:holder ex:expr [ shnex:limit "x" ; shnex:nodes [] ] .""",
            // PathValuesExpression: a well-formed SHACL property path.
            "ex:holder ex:expr [ shnex:pathValues [ sh:inversePath ex:p ; sh:zeroOrMorePath ex:q ] ] .",
            // Key parameters are disjoint: one blank node cannot be two functions.
            "ex:holder ex:expr [ shnex:count [] ; shnex:sum [] ] .",
            // §4: the blank node is not the subject of any other triples.
            "ex:holder ex:expr [ shnex:count [] ; ex:other 1 ] .",
            // Mandatory properties: shnex:nodes for list operators, then/else for if.
            "ex:holder ex:expr [ shnex:limit 1 ] .",
            "ex:holder ex:expr [ shnex:if true ] .",
            // VarExpression: xsd:string, minLength 1.
            """ex:holder ex:expr [ shnex:var "" ] .""",
            "ex:holder ex:expr [ shnex:var 1 ] .",
            // ListExpression: constant members only.
            "ex:holder ex:expr ( [ shnex:var \"x\" ] ) .",
            // OrderBy: shnex:desc is an xsd:boolean.
            """ex:holder ex:expr [ shnex:orderBy [] ; shnex:nodes [] ; shnex:desc "yes" ] .""",
            // A filter shape is a shape, not a literal.
            "ex:holder ex:expr [ shnex:filterShape 1 ; shnex:nodes [] ] .",
            // Nested expressions must be well-formed node expressions.
            "ex:holder ex:expr [ shnex:count [ ex:notAFunction 1 ] ] .",
            // A shnex: property without a key parameter, and an unknown shnex: term.
            "ex:holder ex:expr [ shnex:nodes [] ] .",
            "ex:holder ex:expr [ shnex:noSuchFunction 1 ] .",
            // List parameter functions are a single triple; conformsToShape takes exactly two arguments.
            "ex:holder ex:expr [ sparql:abs 1 , 2 ] .",
            "ex:holder ex:expr [ shnex:conformsToShape ( 1 ) ] .",
            // shnex:arg is an IRI or an xsd:integer.
            """ex:holder ex:expr [ shnex:arg "a" ] .""",
            // A cyclic expression.
            "ex:holder ex:expr _:c . _:c shnex:count _:c .",
        )
        for (ttl in cases) assertThrows(ShapeCompileException::class.java, { expression(ttl) }, ttl)
    }

    @Test
    fun `ill-typed xsd integer lexicals are compile errors`() {
        // xsd:integer is [+-]?[0-9]+ (ASCII digits only): no doubled sign, no other Unicode digits.
        for (lexical in listOf("++1", "+-1", "١", "1٢", "", "1.0")) {
            val literal = "\"$lexical\"^^xsd:integer"
            for (ttl in listOf("ex:holder ex:expr [ shnex:limit $literal ; shnex:nodes [] ] .", "ex:holder ex:expr [ shnex:arg $literal ] .")) {
                assertThrows(ShapeCompileException::class.java, { expression(ttl) }, ttl)
            }
        }
        assertEquals(NodeExpression.Limit(1, NodeExpression.Empty), expression("ex:holder ex:expr [ shnex:limit \"+1\"^^xsd:integer ; shnex:nodes [] ] ."))
        assertEquals(NodeExpression.Arg(0), expression("ex:holder ex:expr [ shnex:arg \"-0\"^^xsd:integer ] ."))
    }

    @Test
    fun `blank nodes that are no node expressions compile to null`() {
        assertNull(expression("ex:holder ex:expr [ ex:p 1 ] ."))
        assertNull(expression("ex:holder ex:expr [ ex:f ( 1 2 ) ] ."))
    }

    @Test
    fun `custom functions are recognised`() {
        val list = "ex:f a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:arg 0 ] ."
        assertEquals(NodeExpression.CustomList(ex("f"), listOf(int(1), int(2))), expression("$list ex:holder ex:expr [ ex:f ( 1 2 ) ] ."))
        val named = """
            ex:G a sh:NamedParameterExpressionFunction ; rdfs:subClassOf sh:NamedParameterExpression ;
              sh:parameter [ sh:path ex:g ; sh:keyParameter true ] , [ sh:path ex:h ] ;
              sh:bodyExpression [ shnex:arg ex:g ] .
        """.trimIndent()
        assertEquals(NodeExpression.CustomNamed(ex("G"), mapOf(ex("g") to int(1))), expression("$named ex:holder ex:expr [ ex:g 1 ] ."))
        assertEquals(
            NodeExpression.CustomNamed(ex("G"), mapOf(ex("g") to int(1), ex("h") to NodeExpression.Empty)),
            expression("$named ex:holder ex:expr [ ex:g 1 ; ex:h [] ] ."),
        )
        // Only declared parameters, each once; a key parameter must be present.
        for (body in listOf("[ ex:g 1 ; ex:other 2 ]", "[ ex:g 1 , 2 ]", "[ ex:h 1 ]")) {
            val outcome = runCatching { expression("$named ex:holder ex:expr $body .") }
            if (body == "[ ex:h 1 ]") {
                // ex:h is not a key parameter: the blank node is no node expression at all.
                assertNull(outcome.getOrThrow(), body)
            } else {
                assertTrue(outcome.exceptionOrNull() is ShapeCompileException, body)
            }
        }
        // A custom key parameter may not collide with a built-in one.
        val clash = "ex:H a sh:NamedParameterExpressionFunction ; rdfs:subClassOf sh:NamedParameterExpression ; sh:parameter [ sh:path shnex:count ; sh:keyParameter true ] ."
        assertThrows(ShapeCompileException::class.java) { expression("$clash ex:holder ex:expr [ shnex:count [] ] .") }
    }

    @Test
    fun `validation positions accept the whole library`() {
        fun compile(values: String) = ShapesCompiler.compile(
            Rdf.parse("$header\nex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values $values ] .", RdfFormat.TURTLE).getTriples(),
            ValidationConfig.default(),
        )
        val ps = compile("[ shnex:count [] ]").referencedPropertyShapes.values.single()
        assertEquals(NodeExpression.Count(NodeExpression.Empty), ps.values)
        // An ill-formed shnex: expression at sh:values is a compile error, not an unsupported feature.
        assertThrows(ShapeCompileException::class.java) { compile("[ shnex:limit -1 ; shnex:nodes [] ]") }
    }

    @Test
    fun `an ill-formed function reached through other bodies fails every compile over the index`() {
        val ttl = """
            ex:holder ex:expr [ ex:usesBad () ] , [ ex:ok () ] .
            ex:usesBad a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ ex:badBody () ] .
            ex:badBody a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .
            ex:unused a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:concat ( [ ex:bad1 () ] [ ex:bad2 () ] ) ] .
            ex:bad1 a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .
            ex:bad2 a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression [ shnex:limit -2 ; shnex:nodes () ] .
            ex:ok a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ; sh:bodyExpression 1 .
        """
        val index = ShapeGraphIndex(Rdf.parse(header + "\n" + ttl, RdfFormat.TURTLE).getTriples())
        val (usesBad, ok) = index.objects(ex("holder"), ex("expr")).partition { index.objects(it as com.geoknoesis.kastor.rdf.RdfResource, ex("usesBad")).isNotEmpty() }
        // The second compile must not find ex:usesBad already queued and pass.
        repeat(2) { assertThrows(ShapeCompileException::class.java) { NodeExpressions.compile(usesBad.single(), index) } }
        // An unused function with two ill-formed callees leaves no error behind for an unrelated expression.
        val unused = index.subjects(com.geoknoesis.kastor.rdf.vocab.RDF.type, ShaclSparqlTerms.ListParameterExpressionFunction)
            .first { it == ex("unused") }
        val body = index.objects(unused, ShaclSparqlTerms.bodyExpression).single()
        assertThrows(ShapeCompileException::class.java) { NodeExpressions.compile(body, index) }
        assertEquals(NodeExpression.CustomList(ex("ok"), emptyList()), NodeExpressions.compile(ok.single(), index))
    }
}
