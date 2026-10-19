package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The semi-naive closure of the memory reasoner equals the naive fixpoint (all enabled rules re-applied to all
 * triples until nothing changes) on random graphs, for every subset of the RDFS rule groups.
 */
class MemoryReasonerSemiNaiveTest {
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val subPropertyOf = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
    private val domain = Iri("http://www.w3.org/2000/01/rdf-schema#domain")
    private val range = Iri("http://www.w3.org/2000/01/rdf-schema#range")
    private val vocabulary = listOf(type, subClassOf, subPropertyOf, domain, range)

    /** The rules as plain functions of the whole triple set, applied until nothing changes. */
    private fun naive(asserted: Collection<RdfTriple>, rules: Set<ReasoningRule>): Set<RdfTriple> {
        val all = LinkedHashSet(asserted)
        while (true) {
            val added = HashSet<RdfTriple>()
            fun pairs(predicate: Iri) = all.filter { it.predicate == predicate }
            if (ReasoningRule.RDFS_SUBCLASS in rules) {
                for (a in pairs(subClassOf)) for (b in pairs(subClassOf)) if (a.obj == b.subject) added += RdfTriple(a.subject, subClassOf, b.obj)
                for (a in pairs(type)) for (b in pairs(subClassOf)) if (a.obj == b.subject) added += RdfTriple(a.subject, type, b.obj)
            }
            if (ReasoningRule.RDFS_SUBPROPERTY in rules) {
                for (a in pairs(subPropertyOf)) for (b in pairs(subPropertyOf)) if (a.obj == b.subject) added += RdfTriple(a.subject, subPropertyOf, b.obj)
                for (s in pairs(subPropertyOf)) for (t in all) if (t.predicate == s.subject && s.obj is Iri) added += RdfTriple(t.subject, s.obj as Iri, t.obj)
            }
            if (ReasoningRule.RDFS_DOMAIN in rules) {
                for (d in pairs(domain)) for (t in all) if (t.predicate == d.subject) added += RdfTriple(t.subject, type, d.obj)
            }
            if (ReasoningRule.RDFS_RANGE in rules) {
                for (r in pairs(range)) for (t in all) if (t.predicate == r.subject) (t.obj as? RdfResource)?.let { added += RdfTriple(it, type, r.obj) }
            }
            if (!all.addAll(added)) return all
        }
    }

    private fun randomGraph(random: Random): List<RdfTriple> {
        val terms = (0 until 6).map { Iri("http://example.org/t$it") }
        val predicates = terms.take(3) + vocabulary
        fun resource(): RdfResource = if (random.nextInt(10) == 0) BlankNode("b${random.nextInt(2)}") else (terms + vocabulary.take(2)).random(random)
        fun obj(): RdfTerm = if (random.nextInt(8) == 0) string("v${random.nextInt(2)}") else resource()
        return List(random.nextInt(4, 16)) { RdfTriple(resource(), predicates.random(random), obj()) }
    }

    @Test
    fun `semi-naive closure equals the naive fixpoint for every rule subset`() {
        val groups = listOf(ReasoningRule.RDFS_SUBCLASS, ReasoningRule.RDFS_SUBPROPERTY, ReasoningRule.RDFS_DOMAIN, ReasoningRule.RDFS_RANGE)
        val random = Random(20261019)
        var inferredSomething = 0
        repeat(400) { round ->
            val graph = randomGraph(random)
            for (mask in 0 until 16) {
                val rules = groups.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
                val expected = naive(graph, rules)
                val actual = MemoryReasoner(ReasonerConfig.rdfs().copy(enabledRules = rules)).closure(graph)
                assertEquals(expected, actual, "round $round, rules $rules, graph $graph")
                if (expected.size > graph.toSet().size) inferredSomething++
            }
        }
        assertTrue(inferredSomething > 1000, "the random graphs exercise the rules ($inferredSomething)")
    }

    @Test
    fun `long chains reach the same fixpoint`() {
        val ex = "http://example.org/"
        val graph = (0 until 60).map { RdfTriple(Iri("${ex}C$it"), subClassOf, Iri("${ex}C${it + 1}")) } +
            (0 until 30).map { RdfTriple(Iri("${ex}p$it"), subPropertyOf, Iri("${ex}p${it + 1}")) } +
            listOf(
                RdfTriple(Iri("${ex}x"), type, Iri("${ex}C0")),
                RdfTriple(Iri("${ex}x"), Iri("${ex}p0"), Iri("${ex}y")),
                RdfTriple(Iri("${ex}p30"), domain, Iri("${ex}C0")),
                RdfTriple(Iri("${ex}p30"), range, Iri("${ex}C30")),
            )
        assertEquals(naive(graph, ReasonerConfig.rdfs().enabledRules), MemoryReasoner(ReasonerConfig.rdfs()).closure(graph))
    }
}
