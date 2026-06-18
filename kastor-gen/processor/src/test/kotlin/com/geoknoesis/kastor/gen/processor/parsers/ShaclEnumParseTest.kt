package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShaclEnumParseTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    @Test
    fun `sh in IRI members are captured as typed IRI values`() {
        val ttl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://ex/#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:DocShape a sh:NodeShape ; sh:targetClass ex:Doc ;
              sh:property [ sh:path ex:status ; sh:datatype xsd:string ;
                            sh:in ( ex:DRAFT ex:ACTIVE ) ] .
        """.trimIndent()
        val shapes = ShaclParser(logger).parseShaclContent(ttl)
        assertEquals(1, shapes.size, "Expected 1 shape")
        assertEquals(1, shapes[0].properties.size, "Expected 1 property")
        val prop = shapes[0].properties.single()
        val typed = prop.inValuesTyped!!
        assertEquals(2, typed.size)
        assertTrue(typed.all { it.isIri })
        assertTrue(typed.any { it.value == "https://ex/#DRAFT" })
    }

    @Test
    fun `sh in literal members are captured as typed literal values`() {
        val ttl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://ex/#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:DocShape a sh:NodeShape ; sh:targetClass ex:Doc ;
              sh:property [ sh:path ex:code ; sh:datatype xsd:string ;
                            sh:in ( "LOW" "HIGH" ) ] .
        """.trimIndent()
        val prop = ShaclParser(logger).parseShaclContent(ttl).single().properties.single()
        val typed = prop.inValuesTyped!!
        assertEquals(2, typed.size)
        assertTrue(typed.none { it.isIri })
        assertTrue(typed.any { it.value == "LOW" })
    }
}
