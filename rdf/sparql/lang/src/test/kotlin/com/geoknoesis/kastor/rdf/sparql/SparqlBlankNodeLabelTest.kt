package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.Syntax
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** The DSL writes blank nodes by core's injective rule ([BlankNode.toString]), as N-Triples and Turtle do. */
class SparqlBlankNodeLabelTest {
    private val p = Iri("urn:p")

    private fun subjectLabels(nodes: List<BlankNode>): List<String> {
        val text = select("o0") { where { nodes.forEachIndexed { i, node -> triple(node, p, `var`("o$i")) } } }.sparql
        val query = QueryFactory.create(text, Syntax.syntaxSPARQL_12)
        val labels = Regex("_:[^\\s]+").findAll(text).map { it.value }.toList()
        assertEquals(nodes.size, labels.size, text)
        assertEquals(nodes.map { it.toString() }, labels, "the DSL and core write the same label")
        assertEquals(true, query.isSelectType)
        return labels
    }

    @Test
    fun `ids a and _ colon a are distinct blank nodes, so they are not joined`() {
        val (first, second) = subjectLabels(listOf(BlankNode("a"), BlankNode("_:a")))
        assertNotEquals(first, second)
    }

    @Test
    fun `an id that is not a valid label, such as a Virtuoso node id, is encoded instead of rejected`() {
        val (label) = subjectLabels(listOf(BlankNode("nodeID://b1")))
        assertEquals(BlankNode("nodeID://b1").toString(), label)
    }
}
