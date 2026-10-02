package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Blank-node keying beyond its caps, in messages with `=`, and literal values in refs. */
class BlankNodeKeyResidualsTest {
    private fun e(local: String): Iri = Iri("http://example.org/k#$local")

    /** The same graph with other blank-node labels and the triples in another order. */
    private fun relabelled(triples: List<RdfTriple>, seed: Long): Pair<MemoryGraph, Map<BlankNode, BlankNode>> {
        val random = Random(seed)
        val names = HashMap<BlankNode, BlankNode>()

        fun rename(term: RdfTerm): RdfTerm =
            if (term is BlankNode) names.getOrPut(term) { BlankNode("z${java.lang.Long.toHexString(random.nextLong())}") } else term

        val shuffled = triples.shuffled(random)
        val renamed = shuffled.map { RdfTriple(rename(it.subject) as RdfResource, it.predicate, rename(it.obj)) }
        return MemoryGraph(renamed.shuffled(random)) to names
    }

    private fun cycle(triples: MutableList<RdfTriple>, owner: RdfResource, name: String, size: Int): List<BlankNode> {
        val nodes = (0 until size).map { BlankNode("$name$it") }
        for (i in 0 until size) {
            triples += RdfTriple(owner, e("has"), nodes[i])
            triples += RdfTriple(nodes[i], e("next"), nodes[(i + 1) % size])
        }
        return nodes
    }

    /** The numbers given to [group] must be the same set whatever the labels and the triple order. */
    private fun assertSameNumbers(triples: List<RdfTriple>, group: List<BlankNode>, requested: Set<BlankNode>) {
        val original = BlankNodeKeys.compute(MemoryGraph(triples), requested)
        assertEquals(requested.size, original.values.toSet().size, "keys are not unique")
        assertEquals(1, original.values.map { it.substringBefore('-') }.toSet().size, "precondition: the nodes share a key")
        val expected = group.map { original.getValue(it) }.toSet()
        for (seed in 1L..8L) {
            val (other, names) = relabelled(triples, seed)
            val keys = BlankNodeKeys.compute(other, requested.mapTo(HashSet()) { names.getValue(it) })
            assertEquals(expected, group.map { keys.getValue(names.getValue(it)) }.toSet(), "numbers moved between the cycles (seed $seed)")
        }
    }

    @Test
    fun `components too large for canonical labelling are still ordered without parser labels`() {
        // A 1,200-cycle and two 600-cycles: all 2,400 nodes share a key, every component is above MAX_CANON_NODES.
        val triples = ArrayList<RdfTriple>()
        val big = cycle(triples, e("s"), "big", 1_200)
        val small = cycle(triples, e("s"), "sma", 600) + cycle(triples, e("s"), "smb", 600)
        assertTrue(600 > BlankNodeKeys.MAX_CANON_NODES)
        assertSameNumbers(triples, big, (big + small).toSet())
    }

    @Test
    fun `tied nodes inside one large component are ordered by their surroundings, not by parser labels`() {
        // One component: a hub with 600 leaves, a 6-cycle and two 3-cycles. Colour refinement cannot tell a node of
        // the 6-cycle from a node of a 3-cycle, and the component is too large for canonical labelling.
        val triples = ArrayList<RdfTriple>()
        val hub = BlankNode("hub")
        triples += RdfTriple(e("s"), e("owns"), hub)
        for (i in 0 until 600) {
            val leaf = BlankNode("leaf$i")
            triples += RdfTriple(hub, e("leaf"), leaf)
            triples += RdfTriple(leaf, e("value"), Literal(i.toString(), XSD.string))
        }
        val six = cycle(triples, hub, "six", 6)
        val three = cycle(triples, hub, "tri", 3) + cycle(triples, hub, "tra", 3)
        assertSameNumbers(triples, six, (six + three).toSet())
    }

    @Test
    fun `a large component is read with a bounded number of lookups and one pass over the graph`() {
        // A chain of 3,000 blank nodes under one class: 6,000 lookups if every node is looked up on its own.
        val triples = ArrayList<RdfTriple>()
        val chain = (0 until 3_000).map { BlankNode("c$it") }
        triples += RdfTriple(e("Owner"), e("has"), chain[0])
        for (i in chain.indices) {
            triples += RdfTriple(chain[i], e("value"), Literal((i % 7).toString(), XSD.string))
            if (i + 1 < chain.size) triples += RdfTriple(chain[i], e("next"), chain[i + 1])
        }
        val graph = MemoryGraph(triples)
        val requested = setOf(chain[0], chain[1_500], chain[2_999])

        val stats = BlankNodeKeys.Stats()
        val batched = BlankNodeKeys.compute(graph, requested, stats = stats)
        assertTrue(stats.lookups <= BlankNodeKeys.MAX_LOOKUPS + 1, "lookups: ${stats.lookups}")
        assertEquals(1, stats.fullReads)
        assertEquals(3_000, stats.describedNodes)

        // The keys do not depend on how the graph was read.
        val unbatched = BlankNodeKeys.Stats()
        assertEquals(BlankNodeKeys.compute(graph, requested, stats = unbatched, maxLookups = Int.MAX_VALUE), batched)
        assertEquals(0, unbatched.fullReads)
        assertTrue(unbatched.lookups >= 6_000, "lookups without the index: ${unbatched.lookups}")
        assertEquals(3, batched.values.toSet().size)
    }

    private fun finding(message: String, value: RdfTerm? = null, keys: Map<BlankNode, String> = emptyMap()): QualityFinding =
        QualityFinding(
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                focusNode = e("focus"),
                message = message,
                value = value,
            ),
            QualityCategory.UNCATEGORIZED,
            null,
            QualityTier.STRUCTURAL,
            keys,
        )

    @Test
    fun `a blank node after an equals sign is stabilised, but not inside an IRI`() {
        val keys = mapOf(BlankNode("b1") to "_:k0123")
        assertEquals("node=_:k0123, other = _:k0123", finding("node=_:b1, other = _:b1", keys = keys).stableMessage)
        assertEquals("[_:k0123] \"_:k0123\" (_:k0123),_:k0123", finding("[_:b1] \"_:b1\" (_:b1),_:b1", keys = keys).stableMessage)
        assertEquals(setOf(BlankNode("b1")), blankNodeReferences("node=_:b1"))
        // A query string, a path with '=' and a prefixed name are IRIs: their text is never rewritten.
        for (iri in listOf("http://ex.org/q?node=_:b1", "<http://ex.org/a;x=_:b1>", "urn:x:y=_:b1", "ex:local=_:b1", "see /path/x=_:b1")) {
            assertEquals(iri, finding(iri, keys = keys).stableMessage)
            assertEquals(emptySet(), blankNodeReferences(iri), iri)
        }
    }

    @Test
    fun `literal values that differ only by language or datatype get different refs`() {
        fun ref(value: RdfTerm): String = FindingRef.from(finding("same message", value)).hexSha256

        val english = ref(Literal("x", "en"))
        val french = ref(Literal("x", "fr"))
        val plain = ref(Literal("x", XSD.string))
        val number = ref(Literal("1", XSD.integer))
        val numberText = ref(Literal("1", XSD.string))
        assertNotEquals(english, french)
        assertNotEquals(english, plain)
        assertNotEquals(number, numberText)
        assertEquals(english, ref(Literal("x", "EN")), "language tags are case-insensitive")
        assertEquals(plain, ref(Literal("x", XSD.string)))
        // No plain string can imitate another literal.
        assertEquals(5, setOf(english, french, plain, ref(Literal("x@en", XSD.string)), ref(Literal("\"x\"@en", XSD.string))).size)
    }
}
