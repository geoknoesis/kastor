package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Compilation of SHACL-SPARQL constraint components (SHACL 1.2 SPARQL §4.1–4.2) into [PropertyConstraint.SparqlComponent]. */
class SparqlConstraintComponentCompileTest {
    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    private val header = """
        @prefix ex: <http://example.com/ns#> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    private fun compile(ttl: String): CompiledShapeGraph =
        ShapesCompiler.compile(Rdf.parse(header + "\n" + ttl, RdfFormat.TURTLE).getTriples(), ValidationConfig.default())

    private fun nodeComponents(ttl: String, shape: String = "S"): List<PropertyConstraint.SparqlComponent> =
        compile(ttl).referencedNodeShapes.getValue(ex(shape)).nodeConstraints.filterIsInstance<PropertyConstraint.SparqlComponent>()

    private fun CompiledShapeGraph.propertyComponents(shape: String) =
        referencedPropertyShapes.getValue(ex(shape)).constraints.filterIsInstance<PropertyConstraint.SparqlComponent>()

    /** A component `ex:C` with a mandatory `ex:lang` parameter and an ASK validator. */
    private val langComponent = """
        ex:C a sh:ConstraintComponent ;
          sh:parameter [ sh:path ex:lang ] ;
          sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (lang(${'$'}value) = ${'$'}lang) }" ] .
    """.trimIndent()

    @Test
    fun `component declared through a subclass is recognized`() {
        val components = nodeComponents(
            """
            ex:ConstraintComponent a rdfs:Class ; rdfs:subClassOf sh:ConstraintComponent .
            ex:SPARQLAskValidator rdfs:subClassOf sh:SPARQLAskValidator .
            ex:TestConstraintComponent a ex:ConstraintComponent ;
              sh:parameter ex:TestParameter1 , ex:TestParameter2 ;
              sh:validator [ a ex:SPARQLAskValidator ; sh:ask '''ASK { FILTER (?value = CONCAT(${'$'}test1, ${'$'}test2)) }''' ] .
            ex:TestParameter1 a sh:Parameter ; sh:path ex:test1 ; sh:datatype xsd:string .
            ex:TestParameter2 a sh:Parameter ; sh:path ex:test2 ; sh:datatype xsd:string .
            ex:S a sh:NodeShape ; ex:test1 "Hello " ; ex:test2 "World" ; sh:targetNode "Hallo Welt" , "Hello World" .
            """.trimIndent(),
        )
        val c = components.single()
        assertEquals(ex("TestConstraintComponent"), c.component)
        assertTrue(c.ask)
        assertEquals(mapOf("test1" to string("Hello "), "test2" to string("World")), c.parameters)
        assertEquals(setOf("value", "test1", "test2"), c.template.preBound)
    }

    @Test
    fun `validator selection order`() {
        val graph = compile(
            """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:lang ] ;
              sh:nodeValidator ex:NodeV ; sh:propertyValidator ex:PropV ; sh:validator ex:AskV .
            ex:NodeV a sh:SPARQLSelectValidator ; sh:select "SELECT ${'$'}this WHERE { ${'$'}this ex:p ?x }" ;
              sh:message "node" .
            ex:PropV a sh:SPARQLSelectValidator ; sh:select "SELECT ${'$'}this ?value WHERE { ${'$'}this ${'$'}PATH ?value }" .
            ex:AskV a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (lang(${'$'}value) = ${'$'}lang) }" .
            ex:AskOnly a sh:ConstraintComponent ; sh:parameter [ sh:path ex:other ] ; sh:validator ex:AskV .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:lang "en" ; ex:other "x" ; sh:property ex:P .
            ex:P sh:path ex:q ; ex:lang "en" .
            """.trimIndent(),
        )
        val node = graph.referencedNodeShapes.getValue(ex("S")).nodeConstraints.filterIsInstance<PropertyConstraint.SparqlComponent>()
        val nodeC = node.single { it.component == ex("C") }
        assertEquals(ex("NodeV"), nodeC.validator)
        assertFalse(nodeC.ask)
        assertEquals(listOf("node"), nodeC.messages.map { it.lexical })
        val askOnly = node.single { it.component == ex("AskOnly") }
        assertEquals(ex("AskV"), askOnly.validator)
        assertTrue(askOnly.ask)

        val prop = graph.propertyComponents("P").single()
        assertEquals(ex("PropV"), prop.validator)
        assertFalse(prop.ask)
        val (text, _) = prop.template.bind(emptyMap())
        assertTrue("<http://example.com/ns#q>" in text, text)
    }

    @Test
    fun `validator that does not fit the shape kind is ignored`() {
        val graph = compile(
            """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:lang ] ;
              sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:select "SELECT ${'$'}this WHERE { }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P .
            ex:P sh:path ex:q ; ex:lang "en" .
            """.trimIndent(),
        )
        assertTrue(graph.propertyComponents("P").isEmpty())
    }

    @Test
    fun `missing mandatory parameter declares no constraint`() {
        val component = """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:opt ; sh:optional true ] , [ sh:path ex:req ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER (${'$'}value != COALESCE(?opt, ${'$'}req)) }" ] .
        """.trimIndent()
        assertTrue(nodeComponents("$component\nex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:opt \"x\" .").isEmpty())

        val both = nodeComponents("$component\nex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:opt \"x\" ; ex:req \"y\" .").single()
        assertEquals(mapOf("opt" to string("x"), "req" to string("y")), both.parameters)
        val reqOnly = nodeComponents("$component\nex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:req \"y\" .").single()
        assertEquals(mapOf("req" to string("y")), reqOnly.parameters)
        // An absent optional parameter stays a pre-bound name, left unbound.
        assertTrue("opt" in reqOnly.template.preBound)
    }

    @Test
    fun `two parameter values give two constraints`() {
        val components = nodeComponents("$langComponent\nex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:lang \"de\" , \"en\" .")
        assertEquals(setOf(string("de"), string("en")), components.map { it.parameters["lang"] }.toSet())
        assertEquals(2, components.size)
    }

    @Test
    fun `ill-formed declarations`() {
        val shape = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:lang \"en\" ; ex:x \"1\" ; <http://other.org/x> \"2\" ; ex:value \"v\" .\n"
        val ask = "sh:validator [ a sh:SPARQLAskValidator ; sh:ask \"ASK { }\" ]"
        val cases = mapOf(
            "parameter name value" to "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:value ] ; $ask .",
            "duplicate local name" to
                "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:x ] , [ sh:path <http://other.org/x> ] ; $ask .",
            "all parameters optional" to "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:lang ; sh:optional true ] ; $ask .",
            "sh:optional not a boolean" to "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:lang ; sh:optional \"yes\" ] ; $ask .",
            "node validator without sh:select" to
                "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:lang ] ; sh:nodeValidator [ a sh:SPARQLSelectValidator ] .",
            "validator without sh:ask" to
                "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:lang ] ; sh:validator [ a sh:SPARQLAskValidator ] .",
            "SELECT without this" to
                "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:lang ] ; " +
                "sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:select \"SELECT ?value WHERE { ?value ex:p ?x }\" ] .",
        )
        for ((name, component) in cases) {
            assertThrows(ShapeCompileException::class.java, { compile(shape + component) }, name)
        }
    }

    @Test
    fun `ill-formed components that no shape uses are not checked`() {
        val shape = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeKind sh:IRI .\n"
        compile(shape + "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:value ] ; sh:validator [ a sh:SPARQLAskValidator ] .")
    }

    @Test
    fun `ASK rebinding value is a pre-binding restriction`() {
        val shapes = """
            ex:Country a rdfs:Class .
            ex:LanguageConstraintComponentUsingASK a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:lang ; sh:datatype xsd:string ; sh:minLength 2 ; sh:name "language" ] ;
              sh:validator ex:hasLang .
            ex:LanguageExampleShape a sh:NodeShape ; sh:property _:b1 , _:b2 ; sh:targetClass ex:Country .
            ex:hasLang a sh:SPARQLAskValidator ;
              sh:ask '''ASK { BIND (true AS ?value) . FILTER (isLiteral(?value) && langMatches(lang(?value), ${'$'}lang)) }''' ;
              sh:message "Values are literals with language \"{?lang}\"" .
            _:b1 sh:path ex:englishLabel ; ex:lang "en" .
            _:b2 sh:path ex:germanLabel ; ex:lang "de" .
        """.trimIndent()
        assertThrows(SparqlPreBindingRestrictionException::class.java) { compile(shapes) }
    }

    @Test
    fun `rebinding an absent optional parameter is a pre-binding restriction`() {
        val shapes = """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:opt ; sh:optional true ] , [ sh:path ex:req ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { BIND (1 AS ?opt) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:req "y" .
        """.trimIndent()
        assertThrows(SparqlPreBindingRestrictionException::class.java) { compile(shapes) }
    }

    @Test
    fun `core components in the sh namespace are not redefined`() {
        val graph = compile(
            """
            sh:PatternConstraintComponent a sh:ConstraintComponent ;
              sh:parameter [ sh:path sh:pattern ] , [ sh:path sh:flags ; sh:optional true ] ;
              sh:validator [ a sh:SPARQLAskValidator ;
                sh:ask '''ASK { FILTER (!isBlank(${'$'}value) && IF(bound(${'$'}flags), regex(str(${'$'}value), ${'$'}pattern, ${'$'}flags), regex(str(${'$'}value), ${'$'}pattern))) }''' ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:pattern "^a" .
            """.trimIndent(),
        )
        val constraints = graph.referencedNodeShapes.getValue(ex("S")).nodeConstraints
        assertTrue(constraints.none { it is PropertyConstraint.SparqlComponent })
        assertTrue(constraints.any { it is PropertyConstraint.Pattern })
    }
}
