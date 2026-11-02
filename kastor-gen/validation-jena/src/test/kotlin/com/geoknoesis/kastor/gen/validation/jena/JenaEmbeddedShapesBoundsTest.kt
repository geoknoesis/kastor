package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.apache.jena.graph.NodeFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.shacl.Shapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * Embedded shapes that use only SHACL Core terms are still bounded: their structure is measured before `jena-shacl`
 * runs ([com.geoknoesis.kastor.gen.runtime.EmbeddedShapesBounds]), and the work the data makes them do is charged to
 * a deterministic step budget ([JenaEmbeddedBudget]). Before, a nested `sh:or` took 12.7 s on a 789-byte graph.
 */
@Timeout(60)
class JenaEmbeddedShapesBoundsTest {
    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://ex/> . " +
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
    private val focus = Iri("http://ex/a")

    private fun graphs(turtle: String): List<RdfGraph> =
        listOf(JenaBridge.fromString(prefixes + turtle, "TURTLE"), MemoryGraph(Rdf.parse(prefixes + turtle, "TURTLE").getTriples()))

    private fun refused(turtle: String, reason: String) {
        for (graph in graphs(turtle)) {
            val error = assertThrows<EmbeddedShapesRefusedException> { JenaValidation().validate(graph, focus) }
            assertTrue(error.message!!.contains(reason), error.message)
            assertThrows<EmbeddedShapesRefusedException> { JenaValidation().validateAll(graph, listOf(focus)) }
        }
    }

    /** The reviewer's probe: `S_i sh:or ( S_i+1 S_i+1 )`, `n` levels. */
    private fun orChain(n: Int): String =
        "ex:S0 a sh:NodeShape ; sh:targetNode ex:a .\n" +
            (0 until n).joinToString("\n") { "ex:S$it sh:or ( ex:S${it + 1} ex:S${it + 1} ) ." } +
            "\nex:S$n sh:class ex:Nope .\nex:a ex:p \"x\" .\n"

    /** `n` nested property shapes over a data graph in which every node has `k` values. */
    private fun fanOut(n: Int, k: Int): String =
        "ex:S0 a sh:NodeShape ; sh:targetNode ex:a .\n" +
            (0 until n).joinToString("\n") { "ex:S$it sh:property [ sh:path ex:p ; sh:node ex:S${it + 1} ] ." } +
            "\nex:S$n sh:class ex:Nope .\n" +
            (0 until k).joinToString("\n") { i -> (0 until k).joinToString("\n") { j -> "ex:a$i ex:p ex:b$j . ex:b$j ex:p ex:a$i ." } } +
            "\n" + (0 until k).joinToString("\n") { "ex:a ex:p ex:b$it ." }

    @Test
    fun `the exponential sh or chain is refused before the engine runs`() {
        for (n in listOf(11, 12, 14, 20, 26)) refused(orChain(n), "unfolded into a tree")
        for (graph in graphs(orChain(8))) assertTrue(JenaValidation().validate(graph, focus) is ValidationResult.Violations)
    }

    @Test
    fun `malformed and oversized lists are refused`() {
        refused("ex:S sh:targetNode ex:a ; sh:or _:l .\n_:l rdf:first ex:T ; rdf:rest _:l .\nex:T sh:class ex:C .\nex:a ex:p 1 .", "cyclic")
        refused("ex:S sh:targetNode ex:a ; sh:in ( ${(0..1000).joinToString(" ")} ) .\nex:a ex:p 1 .", "more than 1000 members")
    }

    @Test
    fun `data that makes admitted shapes branch runs out of the step budget`() {
        refused(fanOut(4, 30), "stopped validating")
        // The same shapes over little data are validated.
        for (graph in graphs(fanOut(4, 3))) assertTrue(JenaValidation().validate(graph, focus) is ValidationResult.Violations)
    }

    @Test
    fun `the step budget is deterministic and charged per focus node`() {
        val graph = RDFParser.create().fromString(prefixes + fanOut(3, 4)).lang(Lang.TURTLE).toGraph()
        val shapes = Shapes.parse(graph)
        val node = NodeFactory.createURI("http://ex/a")
        // Find the exact cost by bisection, twice: it is the same both times.
        fun fits(steps: Long) = runCatching { JenaEmbeddedBudget.validate(shapes, graph, node, steps) }.isSuccess
        fun cost(): Long {
            var low = 0L
            var high = 1_000_000L
            while (low < high) {
                val mid = (low + high) / 2
                if (fits(mid)) high = mid else low = mid + 1
            }
            return low
        }
        val steps = cost()
        assertEquals(steps, cost())
        assertTrue(steps > 64, "$steps")
        assertThrows<EmbeddedShapesRefusedException> { JenaEmbeddedBudget.validate(shapes, graph, node, steps - 1) }
    }

    /** Differential: the budgeted procedure reports what `ShaclValidator.validate(shapes, data, focus)` reports. */
    @Test
    fun `the budgeted validation reports what the engine reports`() {
        val cases = listOf(
            orChain(4),
            fanOut(2, 3),
            "ex:S a sh:NodeShape ; sh:targetClass ex:C ; sh:property [ sh:path ex:p ; sh:minCount 2 ; sh:datatype <http://www.w3.org/2001/XMLSchema#integer> ] .\nex:a a ex:C ; ex:p \"x\" .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:xone ( [ sh:class ex:C ] [ sh:class ex:D ] ) ; sh:not [ sh:class ex:E ] .\nex:a a ex:C, ex:D, ex:E .",
            "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path [ sh:inversePath ex:q ] ; sh:maxCount 0 ] .\nex:a ex:p 1 . ex:b ex:q ex:a .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:qualifiedValueShape [ sh:class ex:C ] ; sh:qualifiedMinCount 2 ] .\nex:a ex:p ex:x . ex:x a ex:C .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ; sh:ignoredProperties ( rdf:type ) ; sh:property [ sh:path ex:p ] .\nex:a ex:q 1 .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node ex:S ; sh:in ( 1 2 ) ] .\nex:a ex:p 3 .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:b ; sh:class ex:C .\nex:a ex:p 1 .",
        )
        val node = NodeFactory.createURI("http://ex/a")
        for (turtle in cases) {
            val graph = RDFParser.create().fromString(prefixes + turtle).lang(Lang.TURTLE).toGraph()
            val shapes = Shapes.parse(graph)
            fun summary(report: org.apache.jena.shacl.ValidationReport) =
                report.entries.map { listOf(it.focusNode(), it.resultPath(), it.value(), it.sourceConstraintComponent(), it.severity()).toString() }.sorted()
            assertEquals(summary(ShaclValidator.get().validate(shapes, graph, node)), summary(JenaEmbeddedBudget.validate(shapes, graph, node)), turtle)
        }
    }

    @Test
    fun `recursive shapes are admitted`() {
        val turtle = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node ex:S ] ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\n" +
            "ex:a ex:p ex:b . ex:b ex:p ex:a ."
        for (graph in graphs(turtle)) assertTrue(JenaValidation().validate(graph, focus) is ValidationResult.Violations)
    }

    @Test
    fun `trusted and constructor shapes are not bounded`() {
        for (graph in graphs(orChain(11))) assertTrue(JenaValidation.trustingEmbeddedShapes().validate(graph, focus) is ValidationResult.Violations)
        val shapes = Rdf.parse(prefixes + orChain(11), "TURTLE")
        assertTrue(JenaValidation(shapes).validate(shapes, focus) is ValidationResult.Violations)
    }
}
