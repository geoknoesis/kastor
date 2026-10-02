package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `list cells named with IRIs are accepted`() {
        // RDF and SHACL lists do not require blank node cells: a SHACL list is "an IRI or a blank node that ...".
        val twoCells = "ex:l1 rdf:first ex:v1 ; rdf:rest ex:l2 . ex:l2 rdf:first ex:v2 ; rdf:rest rdf:nil ."
        assertTrue(validate(shape("sh:property [ sh:path ex:p ; sh:in ex:l1 ] . $twoCells")).isValid)
        assertFalse(validate(shape("sh:property [ sh:path ex:p ; sh:in ex:l1 ] . ex:l1 rdf:first ex:v1 ; rdf:rest rdf:nil .")).isValid)
        // Blank and IRI cells mix: a sequence path whose second cell is named, a logical list whose first cell is.
        val mixedPath = "sh:property [ sh:path [ rdf:first ex:p ; rdf:rest ex:tail ] ; sh:minCount 1 ] . ex:tail rdf:first ex:r ; rdf:rest rdf:nil ."
        assertTrue(validate(shape(mixedPath), "ex:a ex:p ex:b . ex:b ex:r 1 .").isValid)
        assertFalse(validate(shape(mixedPath), "ex:a ex:p ex:b . ex:b ex:q 1 .").isValid)
        val named = "sh:or ex:alternatives . ex:alternatives rdf:first ex:A ; rdf:rest [ rdf:first ex:B ; rdf:rest rdf:nil ] . " +
            "ex:A sh:property [ sh:path ex:missing ; sh:minCount 1 ] . ex:B sh:property [ sh:path ex:q ; sh:minCount 1 ] ."
        assertTrue(validate(shape(named)).isValid)
        assertFalse(validate(shape(named), "ex:a ex:p 1 .").isValid)
        // The well-formedness rules are those of blank node cells.
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:in ex:l1 ] . ex:l1 rdf:first ex:v1 ; rdf:rest ex:l1 ."), "Cyclic")
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:in ex:l1 ] . ex:l1 rdf:first ex:v1 ."), "rdf:rest")
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:in ex:notAList ] ."), "rdf:first")
    }

    @Test
    fun `duplicate triples of a bag-like graph do not make a list ill-formed`() {
        // A provider that returns the same triple twice (no set semantics) must not turn one rdf:first value into two.
        val shapes = Rdf.parse("$prefixes\n" + shape("sh:property [ sh:path ( ex:p ex:r ) ; sh:minCount 1 ; sh:in ( 1 2 ) ] ."), RdfFormat.TURTLE)
        val triples = shapes.getTriples()
        val bag = triples + triples
        val compiled = com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.compile(bag, ValidationConfig.default())
        val path = compiled.orderedNodeShapes.single().propertyShapes.single().path
        assertTrue(path is com.geoknoesis.kastor.rdf.shacl.native.ShaclPath.Sequence && path.segments.size == 2, path.toString())
        val index = com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex(bag)
        val listHead = triples.single { it.predicate.value.endsWith("#in") }
        assertEquals(2, index.parseRdfList(listHead.obj).size)
        assertEquals(1, index.objects(listHead.subject, listHead.predicate).size, "values are a set")
        assertEquals(1, index.subjects(listHead.predicate, listHead.obj).size)
    }

    @Test
    fun `empty and one-member sequence paths are ill-formed`() {
        // SHACL: a sequence path is a list with at least two members. `sh:path ()` used to compile to the predicate
        // rdf:nil and `( ex:p )` to a one-step sequence.
        assertIllFormed(shape("sh:property [ sh:path ( ) ; sh:minCount 1 ] ."), "sequence path")
        assertIllFormed(shape("sh:property [ sh:path ( ex:p ) ; sh:minCount 1 ] ."), "sequence path")
        assertIllFormed(shape("sh:property [ sh:path ( ex:p ( ex:q ) ) ; sh:minCount 1 ] ."), "sequence path")
        assertIllFormed(shape("sh:property [ sh:path [ sh:inversePath ( ) ] ; sh:minCount 1 ] ."), "sequence path")
        assertIllFormed(shape("sh:property [ sh:path [ sh:alternativePath ( ex:p ) ] ; sh:minCount 1 ] ."), "alternative path")
        assertIllFormed(shape("sh:property [ sh:path [ sh:alternativePath ( ) ] ; sh:minCount 1 ] ."), "alternative path")
        assertIllFormed(shape("sh:property [ sh:path ex:p ; sh:equals ( ) ] ."), "sequence path")
        // Two members are enough, nested or not.
        assertTrue(validate(shape("sh:property [ sh:path ( ex:p ex:none ) ; sh:maxCount 0 ] .")).isValid)
        assertTrue(validate(shape("sh:property [ sh:path [ sh:alternativePath ( ex:p ex:q ) ] ; sh:minCount 2 ] .")).isValid)
    }

    @Test
    fun `well-formed and empty lists still compile`() {
        assertTrue(validate(shape("sh:property [ sh:path ex:p ; sh:in ( ex:v1 ex:v2 ) ] .")).isValid)
        assertFalse(validate(shape("sh:property [ sh:path ex:p ; sh:in ( ) ] .")).isValid)
        assertTrue(validate(shape("sh:property [ sh:path ( ex:p ex:none ) ; sh:maxCount 0 ] .")).isValid)
    }
}
