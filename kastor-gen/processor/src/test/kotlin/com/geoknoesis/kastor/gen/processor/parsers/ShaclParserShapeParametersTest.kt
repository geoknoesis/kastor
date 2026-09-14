package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Numeric bounds keep every digit; `sh:severity`, `sh:message` and `sh:deactivated` reach the model. */
class ShaclParserShapeParametersTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <https://example.test/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    @Test
    fun `numeric bounds are parsed exactly`() {
        val shape = ShaclParser(RecordingLogger()).parseShaclContent(
            """
            $prefixes
            ex:AccountShape a sh:NodeShape ; sh:targetClass ex:Account ;
               sh:property [ sh:path ex:balance ; sh:datatype xsd:integer ;
                             sh:maxInclusive 9223372036854775807 ; sh:minExclusive -9223372036854775809 ] ;
               sh:property [ sh:path ex:rate ; sh:datatype xsd:decimal ;
                             sh:minInclusive 0.1000000000000000055511151231257827 ; sh:maxExclusive "1E+2"^^xsd:double ] .
            """.trimIndent(),
        ).single()
        val byPath = shape.properties.associateBy { it.path.substringAfterLast('/') }
        assertEquals(BigDecimal("9223372036854775807"), byPath.getValue("balance").maxInclusive)
        assertEquals(BigDecimal("-9223372036854775809"), byPath.getValue("balance").minExclusive)
        assertEquals(BigDecimal("0.1000000000000000055511151231257827"), byPath.getValue("rate").minInclusive)
        assertEquals(0, BigDecimal("100").compareTo(byPath.getValue("rate").maxExclusive))
    }

    @Test
    fun `severity message and deactivated are read from property and node shapes`() {
        val shapes = ShaclParser(RecordingLogger()).parseShaclContent(
            """
            $prefixes
            ex:DocShape a sh:NodeShape ; sh:targetClass ex:Doc ;
               sh:property [ sh:path ex:code ; sh:datatype xsd:string ; sh:maxLength 3 ;
                             sh:severity sh:Warning ; sh:message "Code is too long"@en, "Code must be short" ] ;
               sh:property [ sh:path ex:legacy ; sh:datatype xsd:string ; sh:minCount 1 ; sh:deactivated true ] ;
               sh:property [ sh:path ex:title ; sh:datatype xsd:string ] .
            ex:OldShape a sh:NodeShape ; sh:targetClass ex:Old ; sh:deactivated "true"^^xsd:boolean ;
               sh:property [ sh:path ex:name ; sh:datatype xsd:string ] .
            """.trimIndent(),
        )
        val doc = shapes.single { it.targetClass.endsWith("Doc") }
        val byPath = doc.properties.associateBy { it.path.substringAfterLast('/') }
        assertEquals("http://www.w3.org/ns/shacl#Warning", byPath.getValue("code").severity)
        assertEquals("Code must be short", byPath.getValue("code").message, "the message without a language tag is preferred")
        assertTrue(byPath.getValue("legacy").deactivated)
        assertNull(byPath.getValue("title").severity)
        assertNull(byPath.getValue("title").message)
        assertFalse(byPath.getValue("title").deactivated)
        assertFalse(doc.deactivated)
        assertTrue(shapes.single { it.targetClass.endsWith("Old") }.deactivated)
    }
}
