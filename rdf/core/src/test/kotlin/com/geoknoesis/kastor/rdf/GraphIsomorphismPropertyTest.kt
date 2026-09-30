package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Seeded property test: [isIsomorphicTo] agrees with a brute-force matcher (trying every blank-node bijection) on
 * small random graphs, their relabelled permutations, near-miss mutations, and blank-node cycle graphs that colour
 * refinement cannot tell apart.
 */
class GraphIsomorphismPropertyTest {
    private val iris = List(3) { Iri("urn:i$it") }
    private val predicates = List(2) { Iri("urn:p$it") }
    private val literals = listOf(string("l0"), string("l1"))

    /** True if some bijection between the blank nodes of [a] and [b] maps the triples of [a] onto those of [b]. */
    private fun bruteForceIsomorphic(a: Set<RdfTriple>, b: Set<RdfTriple>): Boolean {
        if (a.size != b.size) return false
        val left = blankNodes(a)
        val right = blankNodes(b)
        if (left.size != right.size) return false
        fun map(term: RdfTerm, m: Map<BlankNode, BlankNode>): RdfTerm = if (term is BlankNode) m.getValue(term) else term
        fun search(i: Int, m: MutableMap<BlankNode, BlankNode>, used: MutableSet<BlankNode>): Boolean {
            if (i == left.size) {
                return a.mapTo(HashSet()) { RdfTriple(map(it.subject, m) as RdfResource, it.predicate, map(it.obj, m)) } == b
            }
            for (candidate in right) {
                if (candidate in used) continue
                m[left[i]] = candidate
                used += candidate
                if (search(i + 1, m, used)) return true
                used -= candidate
            }
            m.remove(left[i])
            return false
        }
        return search(0, HashMap(), HashSet())
    }

    private fun blankNodes(triples: Set<RdfTriple>): List<BlankNode> =
        triples.flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().distinct()

    private fun randomGraph(random: Random, prefix: String): Set<RdfTriple> {
        val blanks = List(random.nextInt(1, 6)) { BlankNode("$prefix$it") }
        val subjects: List<RdfResource> = blanks + iris
        val objects: List<RdfTerm> = blanks + iris + literals
        return buildSet {
            repeat(random.nextInt(1, 9)) {
                // Mostly blank-node subjects, so blank nodes are well connected.
                val subject = if (random.nextInt(4) > 0) blanks.random(random) else subjects.random(random)
                add(RdfTriple(subject, predicates.random(random), objects.random(random)))
            }
        }
    }

    /** [triples] with every blank node renamed through a random permutation, in a shuffled order. */
    private fun relabel(triples: Set<RdfTriple>, random: Random, prefix: String): List<RdfTriple> {
        val blanks = blankNodes(triples)
        val targets = blanks.indices.shuffled(random).map { BlankNode("$prefix$it") }
        val rename = blanks.zip(targets).toMap()
        fun r(term: RdfTerm): RdfTerm = if (term is BlankNode) rename.getValue(term) else term
        return triples.map { RdfTriple(r(it.subject) as RdfResource, it.predicate, r(it.obj)) }.shuffled(random)
    }

    /** [triples] with one triple changed in one position (possibly into a duplicate or an isomorphic variant). */
    private fun mutate(triples: List<RdfTriple>, random: Random): Set<RdfTriple> {
        val blanks = blankNodes(triples.toSet())
        val index = random.nextInt(triples.size)
        val t = triples[index]
        val changed = when (random.nextInt(3)) {
            0 -> RdfTriple(t.subject, predicates.first { it != t.predicate }, t.obj)
            1 -> RdfTriple(t.subject, t.predicate, (blanks + iris + literals).filter { it != t.obj }.random(random))
            else -> RdfTriple((blanks + iris).filter { it != t.subject }.random(random), t.predicate, t.obj)
        }
        return triples.toMutableList().apply { set(index, changed) }.toSet()
    }

    private fun check(a: Collection<RdfTriple>, b: Collection<RdfTriple>): Boolean {
        val expected = bruteForceIsomorphic(a.toSet(), b.toSet())
        val left = MemoryGraph(a.toList())
        val right = MemoryGraph(b.toList())
        assertEquals(expected, left.isIsomorphicTo(right), "isIsomorphicTo disagrees with brute force:\n$a\nvs\n$b")
        assertEquals(expected, right.isIsomorphicTo(left), "isIsomorphicTo is not symmetric:\n$a\nvs\n$b")
        if (expected) {
            val mapping = left.findBlankNodeMapping(right)!!
            fun m(term: RdfTerm): RdfTerm = if (term is BlankNode) mapping.getValue(term) else term
            assertEquals(b.toSet(), a.mapTo(HashSet()) { RdfTriple(m(it.subject) as RdfResource, it.predicate, m(it.obj)) })
        }
        return expected
    }

    @Test
    fun `isIsomorphicTo agrees with brute force on random graphs, permutations and near misses`() {
        val random = Random(20260930)
        var isomorphic = 0
        var nonIsomorphic = 0
        repeat(300) {
            val graph = randomGraph(random, "a")
            val permuted = relabel(graph, random, "b")
            assertTrue(check(graph, permuted), "a relabelled permutation must be isomorphic")
            isomorphic++
            if (check(graph, mutate(permuted, random))) isomorphic++ else nonIsomorphic++
            if (check(graph, randomGraph(random, "c"))) isomorphic++ else nonIsomorphic++
        }
        assertTrue(nonIsomorphic >= 300, "too few non-isomorphic cases: $nonIsomorphic")
        assertTrue(isomorphic >= 300, "too few isomorphic cases: $isomorphic")
    }

    /** Disjoint directed blank-node cycles of the given lengths, all with one predicate. */
    private fun cycles(lengths: List<Int>, prefix: String): List<RdfTriple> {
        var next = 0
        return lengths.flatMap { length ->
            val nodes = List(length) { BlankNode("$prefix${next++}") }
            nodes.indices.map { RdfTriple(nodes[it], predicates[0], nodes[(it + 1) % length]) }
        }
    }

    @Test
    fun `isIsomorphicTo agrees with brute force on regular cycle graphs that refinement cannot separate`() {
        val random = Random(7)
        val partitions = listOf(listOf(6), listOf(3, 3), listOf(2, 4), listOf(2, 2, 2), listOf(4, 2), listOf(5, 1), listOf(1, 1, 4))
        for (left in partitions) {
            for (right in partitions) {
                val a = cycles(left, "a")
                val b = relabel(cycles(right, "b").toSet(), random, "b")
                assertEquals(left.sorted() == right.sorted(), check(a, b), "cycles $left vs $right")
            }
        }
    }
}
