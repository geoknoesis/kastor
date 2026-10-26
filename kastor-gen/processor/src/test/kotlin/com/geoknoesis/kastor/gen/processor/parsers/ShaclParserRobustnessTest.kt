package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaclParserRobustnessTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    """.trimIndent()

    @Test
    fun `turtle syntax errors fail loudly instead of returning zero shapes`() {
        val logger = RecordingLogger()
        val e = assertFailsWith<IllegalArgumentException> {
            ShaclParser(logger).parseShaclContent("$prefixes\nex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ; sh:property [ sh:path ")
        }
        assertTrue("invalid SHACL Turtle" in e.message!!, e.message)
        assertTrue(logger.errors.isEmpty(), "the processor reports it at the annotated symbol: ${logger.errors}")
    }

    @Test
    fun `blank node shapes and literal sh property do not drop the remaining shapes`() {
        val logger = RecordingLogger()
        val shapes = ShaclParser(logger).parseShaclContent(
            """
            $prefixes
            [] a sh:NodeShape ; sh:targetClass ex:Anonymous ;
               sh:property [ sh:path ex:label ; sh:datatype xsd:string ] .
            ex:BrokenShape a sh:NodeShape ; sh:targetClass ex:Broken ;
               sh:property "not a property shape" ;
               sh:property [ sh:path ex:ok ; sh:datatype xsd:string ] .
            ex:ZedShape a sh:NodeShape ; sh:targetClass ex:Zed ;
               sh:property [ sh:path ex:name ; sh:datatype xsd:string ] .
            """.trimIndent()
        )
        assertEquals(listOf("https://example.test/Anonymous", "https://example.test/Broken", "https://example.test/Zed"), shapes.map { it.targetClass })
        assertEquals("urn:kastor:shape:https://example.test/Anonymous", shapes[0].shapeIri)
        assertEquals(listOf("https://example.test/ok"), shapes[1].properties.map { it.path })
        assertTrue(logger.warnings.any { it.contains("literal") }, logger.warnings.toString())
    }

    @Test
    fun `unsupported constructs are skipped with warnings and supported ones are typed`() {
        val logger = RecordingLogger()
        val shapes = ShaclParser(logger).parseShaclContent(
            """
            $prefixes
            ex:AddressShape a sh:NodeShape ; sh:targetClass ex:Address .
            ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
               sh:property [ sh:path [ sh:inversePath ex:knows ] ; sh:datatype xsd:string ] ;
               sh:property [ sh:path ex:homepage ; sh:nodeKind sh:IRI ] ;
               sh:property [ sh:path ex:address ; sh:node ex:AddressShape ] ;
               sh:property [ sh:path ex:age ; sh:or ( [ sh:datatype xsd:integer ] [ sh:datatype xsd:integer ] ) ] ;
               sh:property [ sh:path ex:born ; sh:datatype xsd:date ; sh:minInclusive "1900-01-01"^^xsd:date ] ;
               sh:property [ sh:path ex:code ; sh:datatype xsd:string ; sh:pattern "^ab" ; sh:flags "i" ] ;
               sh:property [ sh:path ex:untyped ; sh:minCount 1 ] .
            """.trimIndent()
        )
        val person = shapes.single { it.targetClass == "https://example.test/Person" }
        val byPath = person.properties.associateBy { it.path.removePrefix("https://example.test/") }
        assertEquals(setOf("homepage", "address", "age", "born", "code"), byPath.keys)
        assertEquals(byPath.keys.sorted(), person.properties.map { it.path.removePrefix("https://example.test/") })
        assertEquals("http://www.w3.org/ns/shacl#IRI", byPath.getValue("homepage").nodeKind)
        assertEquals("https://example.test/Address", byPath.getValue("address").targetClass)
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", byPath.getValue("age").datatype)
        assertNull(byPath.getValue("born").minInclusive)
        assertEquals("i", byPath.getValue("code").patternFlags)
        assertTrue(logger.warnings.any { it.contains("complex sh:path") }, logger.warnings.toString())
        assertTrue(logger.warnings.any { it.contains("ex:untyped") || it.contains("untyped") }, logger.warnings.toString())
        assertTrue(logger.warnings.any { it.contains("minInclusive") }, logger.warnings.toString())
    }

    @Test
    fun `sh node on node shapes and rdfs subClassOf become parent classes`() {
        val shapes = ShaclParser(RecordingLogger()).parseShaclContent(
            """
            $prefixes
            ex:AgentShape a sh:NodeShape ; sh:targetClass ex:Agent ;
               sh:property [ sh:path ex:name ; sh:datatype xsd:string ] .
            ex:Person rdfs:subClassOf ex:Agent .
            ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person .
            ex:OrgShape a sh:NodeShape ; sh:targetClass ex:Org ; sh:node ex:AgentShape .
            """.trimIndent()
        )
        val parents = shapes.associate { it.targetClass.removePrefix("https://example.test/") to it.parentClasses }
        assertEquals(listOf("https://example.test/Agent"), parents["Person"])
        assertEquals(listOf("https://example.test/Agent"), parents["Org"])
        assertEquals(emptyList(), parents["Agent"])
    }

    @Test
    fun `numeric bounds are parsed by their datatype and bounded in length`() {
        val logger = RecordingLogger()
        val shapes = ShaclParser(logger).parseShaclContent(
            """
            $prefixes
            ex:S a sh:NodeShape ; sh:targetClass ex:T ;
               sh:property [ sh:path ex:a ; sh:datatype xsd:double ; sh:minInclusive "1e999999999"^^xsd:double ] ;
               sh:property [ sh:path ex:b ; sh:datatype xsd:integer ; sh:minInclusive "1.5"^^xsd:integer ] ;
               sh:property [ sh:path ex:c ; sh:datatype xsd:double ; sh:maxInclusive "-2.5E2"^^xsd:double ] ;
               sh:property [ sh:path ex:d ; sh:datatype xsd:decimal ; sh:maxExclusive " 1000.00 "^^xsd:decimal ] ;
               sh:property [ sh:path ex:e ; sh:datatype xsd:double ; sh:maxExclusive "INF"^^xsd:double ] .
            """.trimIndent()
        )
        val byPath = shapes.single().properties.associateBy { it.path.removePrefix("https://example.test/") }
        assertNull(byPath.getValue("a").minInclusive, "a billion-digit bound is not built")
        assertNull(byPath.getValue("b").minInclusive, "ill-formed for its datatype")
        assertEquals("-250", byPath.getValue("c").maxInclusive?.toPlainString())
        assertEquals(0, java.math.BigDecimal("1000").compareTo(byPath.getValue("d").maxExclusive))
        assertNull(byPath.getValue("e").maxExclusive)
        assertEquals(3, logger.warnings.count { it.contains("is not numeric") }, logger.warnings.toString())
    }
}
