package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The shapes graph preprocessing of the RDF4J SHACL validator: linear, checked, and blind to how lists are written. */
class ShaclListsTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
    """.trimIndent()

    private fun triples(body: String) = Rdf.parse(prefixes + "\n" + body, "TURTLE").getTriples()

    private val iriList = "ex:S sh:or ex:L1 . ex:L1 rdf:first ex:A ; rdf:rest ex:L2 . ex:L2 rdf:first ex:B ; rdf:rest rdf:nil ."

    @Test
    fun `IRI list nodes become blank nodes that clash with no blank node of the graph`() {
        val renamed = ShaclLists.withBlankListNodes(triples("$iriList ex:S ex:q _:kastorList0 , _:kastorListx ."))
        val lists = renamed.filter { it.predicate.value.endsWith("#rest") || it.predicate.value.endsWith("#first") }.map { it.subject }
        assertTrue(lists.all { it is BlankNode }, renamed.toString())
        val ids = renamed.flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().map { it.id }.toSet()
        assertEquals(4, ids.size, ids.toString())
        assertEquals(listOf(Iri("http://example.org/A"), Iri("http://example.org/B")), ShaclLists.members(renamed.single { it.predicate.value.endsWith("#or") }.obj) { node, p ->
            renamed.filter { it.subject == node && it.predicate.value == p }.map { it.obj }
        })
    }

    /** The prefix used to rescan every blank node id once per character added to it: quadratic before any deadline. */
    @Test
    fun `the fresh prefix is found in one pass, however the graph names its blank nodes`() {
        // Each id fails a prefix of n x only after comparing its own run of x: a cubic rescan.
        val hostile = (0 until 6_000).map { RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), BlankNode("kastorList" + "x".repeat(it) + "y")) }
        val graph = triples(iriList) + hostile
        val renamed = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { ShaclLists.withBlankListNodes(graph) })
        val taken = hostile.map { (it.obj as BlankNode).id }.toSet()
        val fresh = renamed.map { it.subject }.filterIsInstance<BlankNode>().map { it.id }.toSet()
        assertEquals(2, fresh.size)
        assertTrue(fresh.intersect(taken).isEmpty(), fresh.toString())
        // Longer than every id it could clash with, so no id starts with it (whatever the order of the ids).
        val prefix = ShaclLists.freshPrefix(graph)
        assertTrue(taken.none { it.startsWith(prefix) } && prefix.length == taken.maxOf { it.length } + 1, prefix.length.toString())
    }

    @Test
    fun `the preprocessing runs the deadline check, which can stop it`() {
        val graph = triples(iriList) + (0 until 10_000).map { RdfTriple(Iri("http://example.org/s$it"), Iri("http://example.org/p"), Iri("http://example.org/o")) }
        var renaming = 0
        ShaclLists.withBlankListNodes(graph) { renaming++ }
        assertTrue(renaming >= 10, "checks: $renaming")
        // The search reaches every node from a nesting operand.
        val reachable = triples("ex:S sh:not ex:N0 .") + (0 until 10_000).map { RdfTriple(Iri("http://example.org/N$it"), Iri("http://example.org/p"), Iri("http://example.org/N${it + 1}")) }
        var search = 0
        ShaclSailPatterns.javaEvaluated(reachable) { search++ }
        assertTrue(search >= 5, "checks: $search")
        class Stop : RuntimeException()
        assertFailsWith<Stop> { ShaclLists.withBlankListNodes(graph) { throw Stop() } }
        assertFailsWith<Stop> { ShaclSailPatterns.javaEvaluated(reachable) { throw Stop() } }
    }

    /** Each malformed or unusual list still yields the nested pattern: nothing is read as an empty list. */
    @Test
    fun `a pattern is found in lists of any cells, malformed or long`() {
        val pattern = "[ sh:pattern \"^(a)\\\\1$\" ]"
        val long = (0 until 10_000).joinToString(" ") { "ex:C$it rdf:first [ sh:minLength 1 ] ; rdf:rest ex:C${it + 1} ." } +
            " ex:C10000 rdf:first $pattern ; rdf:rest rdf:nil ."
        val cases = mapOf(
            "IRI cells" to "ex:S sh:or ex:L1 . ex:L1 rdf:first [ sh:minLength 1 ] ; rdf:rest ex:L2 . ex:L2 rdf:first $pattern ; rdf:rest rdf:nil .",
            "mixed cells" to "ex:S sh:and ex:L1 . ex:L1 rdf:first [ sh:minLength 1 ] ; rdf:rest _:b . _:b rdf:first [ sh:maxLength 9 ] ; rdf:rest ex:L3 . ex:L3 rdf:first $pattern ; rdf:rest rdf:nil .",
            "a missing rdf:rest" to "ex:S sh:xone ex:L1 . ex:L1 rdf:first [ sh:minLength 1 ] ; rdf:rest ex:L2 . ex:L2 rdf:first $pattern .",
            "a cycle" to "ex:S sh:or ex:L1 . ex:L1 rdf:first $pattern ; rdf:rest ex:L2 . ex:L2 rdf:first [ sh:minLength 1 ] ; rdf:rest ex:L1 .",
            "an overlong list" to "ex:S sh:or ex:C0 . $long",
            "a list in a list" to "ex:S sh:or ( [ sh:and ex:L1 ] ) . ex:L1 rdf:first [ sh:node ex:N ] ; rdf:rest rdf:nil . ex:N sh:property [ sh:path ex:p ; sh:pattern \"x\" ] .",
        )
        for ((name, body) in cases) {
            for (graph in listOf(triples(body), ShaclLists.withBlankListNodes(triples(body)))) {
                assertEquals(1, ShaclSailPatterns.javaEvaluated(graph).size, name)
            }
        }
    }

    @Test
    fun `an unsupported path inside a sequence path of IRI cells is reported`() {
        val shapes = triples(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:P1 ; sh:minCount 1 ] .
            ex:P1 a rdf:List ; rdf:first ex:p ; rdf:rest ex:P2 .
            ex:P2 a rdf:List ; rdf:first [ sh:zeroOrMorePath ex:q ] ; rdf:rest rdf:nil .
            """.trimIndent(),
        )
        val findings = ShaclSailFeatures.scan(shapes).descriptions
        assertTrue(findings.any { "zeroOrMorePath" in it }, findings.toString())
    }
}
