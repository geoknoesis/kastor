package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Seeded property test: [isIsomorphicTo] agrees with a brute-force matcher (trying every blank-node bijection) on
 * small random graphs, their relabelled permutations, near-miss mutations, and blank-node cycle graphs that colour
 * refinement cannot tell apart. The rich generator adds triple terms (nested, with blank nodes inside),
 * language-tagged, directional and datatyped literals, and up to seven blank nodes.
 */
class GraphIsomorphismPropertyTest {
    private val iris = List(3) { Iri("urn:i$it") }
    private val predicates = List(2) { Iri("urn:p$it") }
    private val literals = listOf(string("l0"), string("l1"))

    /**
     * Literals that differ in exactly one aspect from a neighbour: lexical form, language, base direction, datatype
     * (`"1"^^xsd:integer` and `"01"^^xsd:integer` are different terms), and the boolean singletons.
     */
    private val richLiterals: List<Literal> = literals + listOf(
        LangString("l0", "en"), LangString("l0", "fr"), LangString("l1", "en"),
        LangString("l0", "ar", Direction.RTL), LangString("l0", "ar", Direction.LTR), LangString("l0", "ar"),
        TypedLiteral("1", XSD.integer), TypedLiteral("01", XSD.integer), TypedLiteral("1", XSD.decimal),
        TypedLiteral("l0", XSD.token), TrueLiteral, FalseLiteral,
    )

    /** [term] with every blank node (also inside triple terms) replaced through [rename]. */
    private fun rename(term: RdfTerm, rename: (BlankNode) -> BlankNode): RdfTerm = when (term) {
        is BlankNode -> rename(term)
        is TripleTerm -> TripleTerm(rename(term.triple, rename))
        else -> term
    }

    private fun rename(triple: RdfTriple, rename: (BlankNode) -> BlankNode): RdfTriple =
        RdfTriple(rename(triple.subject, rename) as RdfResource, triple.predicate, rename(triple.obj, rename))

    private fun blankNodes(term: RdfTerm): List<BlankNode> = when (term) {
        is BlankNode -> listOf(term)
        is TripleTerm -> blankNodes(term.triple.subject) + blankNodes(term.triple.obj)
        else -> emptyList()
    }

    private fun blankNodes(triples: Set<RdfTriple>): List<BlankNode> =
        triples.flatMap { blankNodes(it.subject) + blankNodes(it.obj) }.distinct()

    /** True if some bijection between the blank nodes of [a] and [b] maps the triples of [a] onto those of [b]. */
    private fun bruteForceIsomorphic(a: Set<RdfTriple>, b: Set<RdfTriple>): Boolean {
        if (a.size != b.size) return false
        val left = blankNodes(a)
        val right = blankNodes(b)
        if (left.size != right.size) return false
        fun search(i: Int, m: MutableMap<BlankNode, BlankNode>, used: MutableSet<BlankNode>): Boolean {
            if (i == left.size) {
                return a.mapTo(HashSet()) { triple -> rename(triple) { m.getValue(it) } } == b
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

    /** A random object for the rich generator: a blank node, IRI, literal or (up to [depth] levels of) triple term. */
    private fun richObject(random: Random, blanks: List<BlankNode>, depth: Int): RdfTerm = when (random.nextInt(10)) {
        0, 1, 2 -> blanks.random(random)
        3 -> iris.random(random)
        4, 5, 6 -> richLiterals.random(random)
        else -> if (depth == 0) richLiterals.random(random) else {
            val subject: RdfResource = if (random.nextInt(3) > 0) blanks.random(random) else iris.random(random)
            TripleTerm(RdfTriple(subject, predicates.random(random), richObject(random, blanks, depth - 1)))
        }
    }

    /** Up to seven blank nodes, triple-term objects (nested twice at most) and every kind of literal. */
    private fun richGraph(random: Random, prefix: String): Set<RdfTriple> {
        val blanks = List(random.nextInt(1, 8)) { BlankNode("$prefix$it") }
        return buildSet {
            repeat(random.nextInt(1, 11)) {
                val subject: RdfResource = if (random.nextInt(4) > 0) blanks.random(random) else iris.random(random)
                add(RdfTriple(subject, predicates.random(random), richObject(random, blanks, depth = 2)))
            }
        }
    }

    /** [triples] with every blank node renamed through a random permutation, in a shuffled order. */
    private fun relabel(triples: Set<RdfTriple>, random: Random, prefix: String): List<RdfTriple> {
        val blanks = blankNodes(triples)
        val targets = blanks.indices.shuffled(random).map { BlankNode("$prefix$it") }
        val renaming = blanks.zip(targets).toMap()
        return triples.map { triple -> rename(triple) { renaming.getValue(it) } }.shuffled(random)
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

    /** One small change of a literal: its neighbour in lexical form, language, direction or datatype. */
    private fun mutateLiteral(literal: Literal, random: Random): Literal = when {
        literal is LangString -> when (random.nextInt(3)) {
            0 -> LangString(literal.lexical + "x", literal.lang, literal.direction)
            1 -> LangString(literal.lexical, if (literal.lang == "en") "de" else "en", literal.direction)
            else -> LangString(literal.lexical, literal.lang, if (literal.direction == Direction.RTL) null else Direction.RTL)
        }
        random.nextBoolean() -> TypedLiteral(literal.lexical + "0", literal.datatype)
        else -> TypedLiteral(literal.lexical, if (literal.datatype == XSD.string) XSD.token else XSD.string)
    }

    /** [term] changed in one place: a literal aspect, a blank node or IRI swapped, or the inside of a triple term. */
    private fun mutateTerm(term: RdfTerm, blanks: List<BlankNode>, random: Random): RdfTerm = when (term) {
        is Literal -> mutateLiteral(term, random)
        is TripleTerm -> {
            val inner = term.triple
            when (random.nextInt(3)) {
                0 -> TripleTerm(RdfTriple(inner.subject, predicates.first { it != inner.predicate }, inner.obj))
                1 -> TripleTerm(RdfTriple(inner.subject, inner.predicate, mutateTerm(inner.obj, blanks, random)))
                else -> TripleTerm(RdfTriple(mutateTerm(inner.subject, blanks, random) as RdfResource, inner.predicate, inner.obj))
            }
        }
        else -> (blanks + iris).filter { it != term }.random(random)
    }

    /** [triples] with one triple changed in one place, at any depth (possibly into an isomorphic variant). */
    private fun mutateRich(triples: List<RdfTriple>, random: Random): Set<RdfTriple> {
        val blanks = blankNodes(triples.toSet())
        val index = random.nextInt(triples.size)
        val t = triples[index]
        val changed = when (random.nextInt(4)) {
            0 -> RdfTriple(t.subject, predicates.first { it != t.predicate }, t.obj)
            1 -> RdfTriple(mutateTerm(t.subject, blanks, random) as RdfResource, t.predicate, t.obj)
            else -> RdfTriple(t.subject, t.predicate, mutateTerm(t.obj, blanks, random))
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
            assertEquals(b.toSet(), a.mapTo(HashSet()) { triple -> rename(triple) { mapping.getValue(it) } })
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

    @Test
    fun `isIsomorphicTo agrees with brute force on graphs with triple terms, rich literals and up to seven blank nodes`() {
        val random = Random(20261001)
        var isomorphic = 0
        var nonIsomorphic = 0
        var maxBlanks = 0
        var withTripleTerms = 0
        var withBlankInTripleTerm = 0
        var withRichLiterals = 0
        repeat(300) {
            val graph = richGraph(random, "a")
            maxBlanks = maxOf(maxBlanks, blankNodes(graph).size)
            if (graph.any { it.obj is TripleTerm }) withTripleTerms++
            if (graph.any { it.obj is TripleTerm && blankNodes(it.obj).isNotEmpty() }) withBlankInTripleTerm++
            if (graph.any { it.obj is LangString || (it.obj as? Literal)?.datatype == XSD.integer }) withRichLiterals++
            val permuted = relabel(graph, random, "b")
            assertTrue(check(graph, permuted), "a relabelled permutation must be isomorphic")
            isomorphic++
            if (check(graph, mutateRich(permuted, random))) isomorphic++ else nonIsomorphic++
            if (check(graph, richGraph(random, "c"))) isomorphic++ else nonIsomorphic++
        }
        assertTrue(nonIsomorphic >= 300, "too few non-isomorphic cases: $nonIsomorphic")
        assertTrue(isomorphic >= 300, "too few isomorphic cases: $isomorphic")
        assertEquals(7, maxBlanks, "the generator must reach seven blank nodes")
        assertTrue(withTripleTerms >= 100, "too few graphs with triple terms: $withTripleTerms")
        assertTrue(withBlankInTripleTerm >= 50, "too few graphs with blank nodes inside triple terms: $withBlankInTripleTerm")
        assertTrue(withRichLiterals >= 100, "too few graphs with language-tagged or datatyped literals: $withRichLiterals")
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

    /** The same cycles, one level down: every edge is asserted about as a triple term. */
    @Test
    fun `isIsomorphicTo agrees with brute force on cycle graphs inside triple terms`() {
        val random = Random(11)
        val partitions = listOf(listOf(6), listOf(3, 3), listOf(2, 4), listOf(2, 2, 2), listOf(7), listOf(3, 4))
        fun quotedEdges(lengths: List<Int>, prefix: String): Set<RdfTriple> =
            cycles(lengths, prefix).mapTo(LinkedHashSet()) { RdfTriple(iris[0], predicates[1], TripleTerm(it)) }
        for (left in partitions) {
            for (right in partitions) {
                val a = quotedEdges(left, "a")
                val b = relabel(quotedEdges(right, "b"), random, "b")
                assertEquals(left.sorted() == right.sorted(), check(a, b), "quoted cycles $left vs $right")
            }
        }
    }
}
