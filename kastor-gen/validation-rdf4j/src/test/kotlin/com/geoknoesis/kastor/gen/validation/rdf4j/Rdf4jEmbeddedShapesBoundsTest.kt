package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * Embedded shapes that use only SHACL Core terms are still bounded: their structure is measured before ShaclSail
 * builds a plan ([com.geoknoesis.kastor.gen.runtime.EmbeddedShapesBounds]). Before, a nested `sh:or` ran ShaclSail
 * out of memory on a 591-byte graph, a cyclic `sh:or` list hung it and a `sh:in` list of 10,000 members overflowed
 * its stack.
 */
@Timeout(60)
class Rdf4jEmbeddedShapesBoundsTest {
    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://ex/> . " +
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
    private val focus = Iri("http://ex/a")

    private fun graph(turtle: String): RdfGraph = Rdf.parse(prefixes + turtle, "TURTLE")

    private fun refused(turtle: String, reason: String) {
        val graph = graph(turtle)
        val error = assertThrows<EmbeddedShapesRefusedException> { Rdf4jValidation().validate(graph, focus) }
        assertTrue(error.message!!.contains(reason), error.message)
        assertTrue(error.message!!.contains("Rdf4jValidation.trustingEmbeddedShapes()"), error.message)
        assertThrows<EmbeddedShapesRefusedException> { Rdf4jValidation().validateAll(graph, listOf(focus)) }
    }

    private fun orChain(n: Int): String =
        "ex:S0 a sh:NodeShape ; sh:targetNode ex:a .\n" +
            (0 until n).joinToString("\n") { "ex:S$it sh:or ( ex:S${it + 1} ex:S${it + 1} ) ." } +
            "\nex:S$n sh:class ex:Nope .\nex:a ex:p \"x\" .\n"

    @Test
    fun `the exponential sh or chain is refused before ShaclSail builds a plan`() {
        for (n in listOf(11, 12, 14, 20, 26)) refused(orChain(n), "unfolded into a tree")
        assertTrue(Rdf4jValidation().validate(graph(orChain(8)), focus) is ValidationResult.Violations)
    }

    @Test
    fun `lists ShaclSail cannot handle are refused`() {
        // Hung ShaclSail.
        refused("ex:S sh:targetNode ex:a ; sh:or _:l .\n_:l rdf:first ex:T ; rdf:rest _:l .\nex:T sh:class ex:C .\nex:a ex:p 1 .", "cyclic")
        refused("ex:S sh:targetNode ex:a ; sh:in _:l .\n_:l rdf:first 1 ; rdf:rest _:m . _:m rdf:first 2 ; rdf:rest _:l .\nex:a ex:p 1 .", "cyclic")
        // Overflowed its stack (sh:in) and its operands.
        refused("ex:S sh:targetNode ex:a ; sh:in ( ${(0 until 10_000).joinToString(" ")} ) .\nex:a ex:p 1 .", "more than 1000 members")
        refused("ex:S sh:targetNode ex:a ; sh:or ( ${(0 until 4_000).joinToString(" ") { "[ sh:class ex:C$it ]" }} ) .\nex:a ex:p 1 .", "more than 64 members")
    }

    @Test
    fun `nesting past the depth cap is refused`() {
        val chain = "ex:S0 a sh:NodeShape ; sh:targetNode ex:a .\n" + (0 until 40).joinToString("\n") { "ex:S$it sh:node ex:S${it + 1} ." } + "\nex:a ex:p 1 ."
        refused(chain, "levels deep")
    }

    @Test
    fun `trusted and constructor shapes are not bounded`() {
        assertTrue(Rdf4jValidation.trustingEmbeddedShapes().validate(graph(orChain(11)), focus) is ValidationResult.Violations)
        val shapes = graph(orChain(11))
        assertTrue(Rdf4jValidation(shapes).validate(shapes, focus) is ValidationResult.Violations)
    }
}
