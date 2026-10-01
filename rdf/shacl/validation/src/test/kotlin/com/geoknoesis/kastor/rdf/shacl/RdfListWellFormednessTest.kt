package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An RDF list cell in the shapes graph must have exactly one `rdf:first` and exactly one `rdf:rest`. A missing or
 * repeated `rdf:rest` used to end the list silently, truncating `sh:in`, `sh:or` or a sequence path.
 */
class RdfListWellFormednessTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun validate(shapes: String, data: String = "ex:a ex:p ex:v2 ; ex:q 1 .") =
        NativeShaclValidator(ValidationConfig.default())
            .validate(Rdf.parse("$prefixes\n$data", RdfFormat.TURTLE), Rdf.parse("$prefixes\n$shapes", RdfFormat.TURTLE))

    private fun assertIllFormed(shapes: String, expected: String) {
        val error = assertThrows(ShaclValidationException::class.java, { validate(shapes) }, shapes)
        assertTrue(error.cause is ShapeCompileException, "$shapes: $error")
        assertTrue(error.message.orEmpty().contains(expected), "$shapes: ${error.message}")
    }

    private fun shape(constraint: String) = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; $constraint"

    @Test
    fun `a list cell without rdf rest is rejected`() {
        // Before: read as ( ex:v1 ), so ex:v2 was a violation of a list the author never wrote.
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:in [ rdf:first ex:v1 ] ] ."), "rdf:rest")
        assertIllFormed(
            shape("sh:property [ sh:path ex:p ; sh:in [ rdf:first ex:v1 ; rdf:rest [ rdf:first ex:v2 ] ] ] ."),
            "rdf:rest",
        )
    }

    @Test
    fun `a list cell with several rdf rest values is rejected`() {
        // Before: the list ended at the forked cell and ex:v2 was not allowed.
        assertIllFormed(
            shape("sh:property [ sh:path ex:p ; sh:in _:l ] . _:l rdf:first ex:v1 ; rdf:rest _:m , rdf:nil . _:m rdf:first ex:v2 ; rdf:rest rdf:nil ."),
            "rdf:rest",
        )
    }

    @Test
    fun `a list cell with a missing or repeated rdf first is rejected`() {
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:in [ rdf:rest rdf:nil ] ] ."), "rdf:first")
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:in [ rdf:first ex:v1 , ex:v2 ; rdf:rest rdf:nil ] ] ."), "rdf:first")
    }

    @Test
    fun `truncated logical operand lists and sequence paths are rejected`() {
        assertIllFormed(
            shape("sh:or [ rdf:first ex:A ; rdf:rest [ rdf:first ex:B ] ] . ex:A sh:property [ sh:path ex:p ; sh:minCount 1 ] . ex:B sh:property [ sh:path ex:q ; sh:minCount 1 ] ."),
            "rdf:rest",
        )
        assertIllFormed(shape("sh:property [ sh:path [ rdf:first ex:p ; rdf:rest [ rdf:first ex:q ] ] ; sh:minCount 1 ] ."), "rdf:rest")
        assertIllFormed(
            shape("sh:property [ sh:path [ sh:alternativePath [ rdf:first ex:p ; rdf:rest [ rdf:first ex:q ] ] ] ; sh:minCount 1 ] ."),
            "rdf:rest",
        )
    }

    @Test
    fun `well-formed and empty lists still compile`() {
        assertTrue(validate(shape("sh:property [ sh:path ex:p ; sh:in ( ex:v1 ex:v2 ) ] .")).isValid)
        assertFalse(validate(shape("sh:property [ sh:path ex:p ; sh:in ( ) ] .")).isValid)
        assertTrue(validate(shape("sh:property [ sh:path ( ex:p ex:none ) ; sh:maxCount 0 ] .")).isValid)
    }
}
