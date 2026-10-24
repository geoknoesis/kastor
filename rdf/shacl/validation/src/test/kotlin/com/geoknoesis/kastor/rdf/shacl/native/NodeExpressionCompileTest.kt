package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Compilation of SHACL 1.2 SPARQL node expressions (SPARQL §6) and constant `sh:values` / `sh:defaultValue`. */
class NodeExpressionCompileTest {
    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    private val header = """
        @prefix ex: <http://example.com/ns#> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
    """.trimIndent()

    private fun triples(ttl: String) = Rdf.parse(header + "\n" + ttl, RdfFormat.TURTLE).getTriples()

    private fun compile(ttl: String, config: ValidationConfig = ValidationConfig.default()): CompiledShapeGraph =
        ShapesCompiler.compile(triples(ttl), config)

    /** Compiles the single object of `ex:holder ex:expr ...`. */
    private fun expression(ttl: String): NodeExpression? {
        val index = ShapeGraphIndex(triples(ttl))
        val term = index.objects(ex("holder"), ex("expr")).single()
        return NodeExpressions.compile(term, index)
    }

    private fun propertyShape(ttl: String, shape: String = "P") = compile(ttl).referencedPropertyShapes.getValue(ex(shape))

    @Test
    fun `sparqlExpr compiles to a select of result`() {
        val e = expression("""ex:holder ex:expr [ sh:sparqlExpr "STRLEN(STR(${'$'}this))" ] .""") as NodeExpression.Select
        assertEquals("result", e.variable)
        assertEquals(setOf("this"), e.template.preBound)
    }

    @Test
    fun `sparqlExpr with prefixes puts the declared prologue first`() {
        val e = expression(
            """
            ex:holder ex:expr [ sh:prefixes <http://example.com/ns> ; sh:sparqlExpr "STRLEN(STR(ex:a))" ] .
            <http://example.com/ns> a owl:Ontology ; sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.com/ns#"^^xsd:anyURI ] .
            """.trimIndent(),
        ) as NodeExpression.Select
        assertEquals("result", e.variable)
    }

    @Test
    fun `select compiles with its single projected variable`() {
        val e = expression("""ex:holder ex:expr [ sh:select "SELECT ?x WHERE { ${'$'}this ?p ?x }" ] .""") as NodeExpression.Select
        assertEquals("x", e.variable)
    }

    @Test
    fun `constants compile to Constant`() {
        assertEquals(NodeExpression.Constant(ex("x")), expression("ex:holder ex:expr ex:x ."))
        assertEquals(NodeExpression.Constant(TypedLiteral("1", XSD.integer)), expression("ex:holder ex:expr 1 ."))
    }

    @Test
    fun `select expression must project exactly one variable`() {
        for (query in listOf("SELECT * {}", "SELECT ?a ?b {}")) {
            assertThrows(ShapeCompileException::class.java, { expression("""ex:holder ex:expr [ sh:select "$query" ] .""") }, query)
        }
    }

    @Test
    fun `select expression syntax rules`() {
        val cases = listOf(
            """ex:holder ex:expr ex:i . ex:i sh:select "SELECT ?x {}" .""",
            """ex:holder ex:expr [ sh:select "SELECT ?x {}" , "SELECT ?y {}" ] .""",
            """ex:holder ex:expr [ sh:select "SELECT ?x {}" ; sh:prefixes <urn:a> , <urn:b> ] .""",
            """ex:holder ex:expr [ sh:select 1 ] .""",
            """ex:holder ex:expr [ sh:sparqlExpr "1" , "2" ] .""",
            """ex:holder ex:expr [ sh:select "SELECT ?x {}" ; sh:prefixes "x" ] .""",
            """ex:holder ex:expr [ a sh:SelectExpression ] .""",
            """ex:holder ex:expr [ a sh:SPARQLExprExpression ] .""",
        )
        for (ttl in cases) assertThrows(ShapeCompileException::class.java, { expression(ttl) }, ttl)
    }

    @Test
    fun `this is pre-bound and may not be rebound`() {
        assertThrows(SparqlPreBindingRestrictionException::class.java) {
            expression("""ex:holder ex:expr [ sh:select "SELECT ?x { BIND (1 AS ${'$'}this) }" ] .""")
        }
    }

    @Test
    fun `shnex expressions compile at values and default value`() {
        for (predicate in listOf("sh:values", "sh:defaultValue")) {
            val p = propertyShape("ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p ; $predicate [ shnex:count [ shnex:pathValues ex:q ] ] .")
            val expr = if (predicate == "sh:values") p.values else p.defaultValue
            assertTrue(expr is NodeExpression.Count, "$predicate: $expr")
        }
    }

    @Test
    fun `a blank node that is no node expression is reported and never silently ignored`() {
        for (predicate in listOf("sh:values", "sh:defaultValue", "sh:expression")) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; $predicate [ ex:undeclared 1 ] ] ."
            val e = assertThrows(UnsupportedShaclFeatureException::class.java, { compile(shapes) }, predicate)
            assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), e.features, predicate)
        }
    }

    @Test
    fun `sparql expressions in values and default value are supported`() {
        val p = propertyShape(
            """ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p ; sh:values [ sh:sparqlExpr "1" ] ; sh:defaultValue [ sh:select "SELECT ?x {}" ] .""",
        )
        assertTrue(p.values is NodeExpression.Select)
        assertTrue(p.defaultValue is NodeExpression.Select)
    }

    @Test
    fun `sparql expression at targetWhere stays unsupported`() {
        val shapes = """ex:S a sh:NodeShape ; sh:class ex:C ; sh:targetWhere [ sh:select "SELECT ?this {}" ] ."""
        val e = assertThrows(UnsupportedShaclFeatureException::class.java) { compile(shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION), e.features)
    }

    @Test
    fun `sh values only on predicate paths`() {
        val cases = listOf(
            "ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path [ sh:inversePath ex:p ] ; sh:values ex:x .",
            "ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p ; sh:values ex:x , ex:y .",
            "ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p ; sh:defaultValue ex:x , ex:y .",
            "ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path [ sh:inversePath ex:p ] ; sh:defaultValue ex:x .",
        )
        for (ttl in cases) assertThrows(ShapeCompileException::class.java, { compile(ttl) }, ttl)
    }

    @Test
    fun `constant values and default value compile`() {
        val p = propertyShape("ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p ; sh:values ex:x ; sh:defaultValue 1 .")
        assertEquals(NodeExpression.Constant(ex("x")), p.values)
        assertEquals(NodeExpression.Constant(TypedLiteral("1", XSD.integer)), p.defaultValue)
        val plain = propertyShape("ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p .")
        assertNull(plain.values)
        assertNull(plain.defaultValue)
    }

    @Test
    fun `values and defaultValue on a shape without sh path are ill-formed`() {
        // SHACL 1.2 Core 3.3: only property shapes with a predicate path have sh:values / sh:defaultValue.
        for (predicate in listOf("sh:values", "sh:defaultValue")) {
            for (value in listOf("ex:x", "[ sh:select \"SELECT ?v WHERE { BIND (1 AS ?v) }\" ]")) {
                assertThrows(ShapeCompileException::class.java, {
                    compile("ex:S a sh:NodeShape ; sh:targetNode ex:a ; $predicate $value .")
                }, "$predicate $value")
            }
            // A deactivated shape is not compiled, so it is not checked either.
            compile("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated true ; $predicate ex:x .")
        }
    }

    @Test
    fun `targetNode select is a target expression`() {
        val shapes = """
            ex:ChildShape a sh:NodeShape ;
              sh:targetNode [ sh:select "SELECT ?person WHERE { ?person a ex:Person }" ] ;
              sh:property [ sh:path ex:driversLicense ; sh:maxCount 0 ] .
        """.trimIndent()
        val targets = compile(shapes).referencedNodeShapes.getValue(ex("ChildShape")).targets
        assertEquals(1, targets.targetNodeExpressions.size)
        assertEquals("person", (targets.targetNodeExpressions.single() as NodeExpression.Select).variable)
        assertTrue(targets.targetNodes.none { it is BlankNode })
    }

    @Test
    fun `targetNode constants stay ordinary targets`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a , 1 ; sh:class ex:C ."
        val targets = compile(shapes).referencedNodeShapes.getValue(ex("S")).targets
        assertEquals(setOf<RdfTerm>(ex("a"), TypedLiteral("1", XSD.integer)), targets.targetNodes.toSet())
        assertTrue(targets.targetNodeExpressions.isEmpty())
    }
}
