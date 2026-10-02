package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Blank-node keys are the same whatever the parser labels, the order of the triples and the RDF syntax; they
 * change only with the node's own structure or its owner chain; and computing them looks only at the blank nodes
 * around the findings.
 */
class BlankNodeKeyStabilityTest {
    private val prefixes =
        """
        @prefix : <http://example.org/r#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :p a owl:ObjectProperty . :q a owl:ObjectProperty .
        """.trimIndent()

    private fun r(local: String): Iri = Iri("http://example.org/r#$local")

    private fun e(local: String): Iri = Iri("http://example.org/k#$local")

    private fun blankNodes(graph: RdfGraph): Set<BlankNode> =
        graph.getTriples().flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().toSet()

    private fun allKeys(graph: RdfGraph): Map<BlankNode, String> = BlankNodeKeys.compute(graph, blankNodes(graph))

    /** The same graph with other blank-node labels and the triples in another order. */
    private fun relabelled(triples: List<RdfTriple>, seed: Long): Pair<MemoryGraph, Map<BlankNode, BlankNode>> {
        val random = Random(seed)
        val names = HashMap<BlankNode, BlankNode>()
        fun rename(term: RdfTerm): RdfTerm =
            if (term is BlankNode) names.getOrPut(term) { BlankNode("z${java.lang.Long.toHexString(random.nextLong())}") } else term
        // Names are given in a shuffled order, so label order and triple order are both unrelated to the original.
        val shuffled = triples.shuffled(random)
        val renamed = shuffled.map { RdfTriple(rename(it.subject) as RdfResource, it.predicate, rename(it.obj)) }
        return MemoryGraph(renamed.shuffled(random)) to names
    }

    /** The restriction on [property] in [graph]. */
    private fun restrictionOn(graph: RdfGraph, property: String): BlankNode =
        graph.getTriples().single { it.predicate == OWL.onProperty && it.obj == r(property) }.subject as BlankNode

    @Test
    fun `keys are the same after relabelling and reordering, also for nodes that only their siblings tell apart`() {
        val graph =
            Rdf.parse(
                """
                $prefixes
                :A a owl:Class ;
                   rdfs:subClassOf [ a owl:Class ; owl:intersectionOf ( :X [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ) ] ,
                                   [ a owl:Class ; owl:intersectionOf ( :Y [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ) ] ,
                                   [ a owl:Restriction ; owl:onProperty :q ; owl:someValuesFrom [ a owl:Class ; owl:unionOf ( :A :B ) ] ] .
                """.trimIndent(),
                "TURTLE",
            )
        val triples = graph.getTriples()
        val original = allKeys(MemoryGraph(triples))
        assertEquals(blankNodes(graph).size, original.values.toSet().size, "keys are not unique: $original")
        for (seed in 1L..8L) {
            val (other, names) = relabelled(triples, seed)
            val keys = allKeys(other)
            for ((node, key) in original) {
                assertEquals(key, keys.getValue(names.getValue(node)), "the key of $node changed with labels / triple order (seed $seed)")
            }
        }
    }

    @Test
    fun `duplicates that refinement cannot tell apart keep their numbers when they are not interchangeable`() {
        // A 6-cycle and two 3-cycles: every node looks the same to colour refinement, but a node of the 6-cycle is not
        // interchangeable with a node of a 3-cycle.
        val triples = ArrayList<RdfTriple>()
        fun cycle(name: String, size: Int): List<BlankNode> {
            val nodes = (0 until size).map { BlankNode("$name$it") }
            for (i in 0 until size) {
                triples += RdfTriple(e("s"), e("has"), nodes[i])
                triples += RdfTriple(nodes[i], e("next"), nodes[(i + 1) % size])
            }
            return nodes
        }
        val six = cycle("six", 6)
        val three = cycle("tri", 3) + cycle("tra", 3)
        val original = allKeys(MemoryGraph(triples))
        assertEquals(12, original.values.toSet().size, "keys are not unique: $original")
        assertEquals(1, original.values.map { it.substringBefore('-') }.toSet().size, "precondition: the twelve nodes share a key")
        val sixKeys = six.map { original.getValue(it) }.toSet()
        val threeKeys = three.map { original.getValue(it) }.toSet()
        for (seed in 1L..12L) {
            val (other, names) = relabelled(triples, seed)
            val keys = allKeys(other)
            assertEquals(sixKeys, six.map { keys.getValue(names.getValue(it)) }.toSet(), "numbers moved between the cycles (seed $seed)")
            assertEquals(threeKeys, three.map { keys.getValue(names.getValue(it)) }.toSet(), "numbers moved between the cycles (seed $seed)")
        }
    }

    @Test
    fun `editing one member of a list leaves the keys of its siblings unchanged`() {
        fun ontology(first: String, filler: String, extra: String = ""): RdfGraph =
            Rdf.parse(
                """
                $prefixes
                :A a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( $extra $first
                    [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ]
                    [ a owl:Restriction ; owl:onProperty :q ; owl:allValuesFrom $filler ] ) ] .
                """.trimIndent(),
                "TURTLE",
            )
        fun key(graph: RdfGraph, property: String): String = allKeys(graph).getValue(restrictionOn(graph, property))
        val before = ontology(":Base", ":D")
        val siblingEdited = ontology(":Base", ":E")
        assertEquals(key(before, "p"), key(siblingEdited, "p"), "editing the restriction on :q re-keyed the restriction on :p")
        assertNotEquals(key(before, "q"), key(siblingEdited, "q"), "an edited restriction keeps its key")
        assertEquals(key(before, "p"), key(ontology(":Other", ":D"), "p"), "replacing an IRI member re-keyed a sibling")
        assertEquals(key(before, "p"), key(ontology(":Base", ":D", extra = ":First"), "p"), "inserting a member re-keyed a sibling")
        // The owner changes with its content, and the same member under another owner has another key.
        val owner = { g: RdfGraph -> g.getTriples().single { it.predicate == OWL.equivalentClass }.obj as BlankNode }
        assertNotEquals(allKeys(before).getValue(owner(before)), allKeys(siblingEdited).getValue(owner(siblingEdited)))
        val underB = Rdf.parse(before.serialize(RdfFormat.N_TRIPLES).replace("http://example.org/r#A", "http://example.org/r#B"), "NTRIPLES")
        assertNotEquals(key(before, "p"), key(underB, "p"), "the owner chain is part of the key")
    }

    private val shapes =
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix ex: <http://example.org/shapes#> .
        ex:RestrictionNeedsSome a sh:NodeShape ;
            sh:targetClass owl:Restriction ;
            sh:property [ sh:path owl:someValuesFrom ; sh:minCount 1 ; sh:severity sh:Warning ; sh:message "restriction without someValuesFrom" ] .
        """.trimIndent()

    private val catalog =
        object : ShapeCatalog {
            override val id = "test"
            override val name = "test"
            override val version = "1"
            override val shapeMetadata: Map<String, ShapeMetadata> = emptyMap()

            override fun loadShapesGraph(): RdfGraph = Rdf.parse(shapes, "TURTLE")
        }

    private fun checker(): QualityChecker = QualityChecker.builder(ShaclValidation.validator()).addCatalog(catalog).build()

    private val ontology =
        """
        $prefixes
        :A a owl:Class ; rdfs:subClassOf :Top ,
            [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ,
            [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ,
            [ a owl:Class ; owl:intersectionOf ( :X [ a owl:Restriction ; owl:onProperty :q ; owl:allValuesFrom :C ] ) ] .
        :B a owl:Class ; rdfs:subClassOf :A ,
            [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom [ a owl:Class ; owl:unionOf ( :A :B ) ] ] .
        """.trimIndent()

    private fun restrictionRefs(report: QualityReport): List<String> =
        report.findings.filter { it.violation.message == "restriction without someValuesFrom" }.map { FindingRef.from(it).hexSha256 }.sorted()

    @Test
    fun `refs are the same for Turtle, N-Triples and RDF-XML serialisations of one ontology`() {
        val turtle = Rdf.parse(ontology, "TURTLE")
        val expected = restrictionRefs(checker().check(turtle))
        assertEquals(4, expected.toSet().size, "four restrictions, four refs: $expected")
        for (format in listOf(RdfFormat.TURTLE, RdfFormat.N_TRIPLES, RdfFormat.RDF_XML)) {
            val text = turtle.serialize(format)
            repeat(2) {
                assertEquals(expected, restrictionRefs(checker().check(Rdf.parse(text, format))), "refs differ for ${format.formatName}")
            }
        }
    }

    @Test
    fun `refs of blank-node findings are exactly the same with every available reasoner`() {
        val graph = Rdf.parse(ontology, "TURTLE")
        val asserted = restrictionRefs(checker().check(graph))
        assertEquals(4, asserted.toSet().size)
        val profiles = OntoQualityReasoningProfile.entries.filter { OntoQualityReasoning.supports(it) }
        assertTrue(OntoQualityReasoningProfile.RDFS in profiles && OntoQualityReasoningProfile.OWL_RL in profiles, "reasoners under test: $profiles")
        for (profile in profiles) {
            assertEquals(asserted, restrictionRefs(checker().check(graph, profile)), "refs differ with reasoner $profile")
        }
    }

    /** A graph with subject and blank-node-object indexes, counting how often it is read in full. */
    private class IndexedGraph(private val triples: List<RdfTriple>) : RdfGraph {
        private val bySubject = HashMap<RdfResource, MutableList<RdfTriple>>()
        private val byBlankObject = HashMap<RdfTerm, MutableList<RdfTriple>>()
        var fullReads = 0
            private set

        init {
            for (t in triples) {
                bySubject.getOrPut(t.subject) { ArrayList(4) }.add(t)
                if (t.obj is BlankNode) byBlankObject.getOrPut(t.obj) { ArrayList(1) }.add(t)
            }
        }

        override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
            val candidates: List<RdfTriple> =
                when {
                    subject != null -> bySubject[subject].orEmpty()
                    obj is BlankNode -> byBlankObject[obj].orEmpty()
                    predicate == RDF.reifies -> emptyList()
                    else -> getTriples()
                }
            return candidates.filter { (predicate == null || it.predicate == predicate) && (obj == null || it.obj == obj) }
        }

        override fun hasTriple(triple: RdfTriple): Boolean = bySubject[triple.subject]?.contains(triple) == true

        override fun getTriples(): List<RdfTriple> {
            fullReads++
            return triples
        }

        override fun size(): Int = triples.size
    }

    @Test
    fun `only the blank nodes around the findings are described, whatever the size of the graph`() {
        val classes = 100_000
        val triples = ArrayList<RdfTriple>(classes * 7)
        val restriction: Iri = OWL.Restriction
        val one = Literal("1", XSD.string)
        fun expression(owner: Iri, id: String) {
            val outer: RdfResource = BlankNode("r$id")
            val middle: RdfResource = BlankNode("u$id")
            val inner: RdfResource = BlankNode("w$id")
            triples += RdfTriple(owner, RDFS.subClassOf, outer)
            triples += RdfTriple(outer, RDF.type, restriction)
            triples += RdfTriple(outer, OWL.onProperty, r("p"))
            triples += RdfTriple(outer, OWL.allValuesFrom, middle)
            triples += RdfTriple(middle, OWL.complementOf, inner)
            triples += RdfTriple(inner, OWL.onProperty, r("q"))
            triples += RdfTriple(inner, OWL.hasValue, one)
        }
        for (i in 0 until classes) expression(Iri("http://example.org/big#C$i"), i.toString())
        // The class of the findings has a second, identical expression.
        expression(Iri("http://example.org/big#C4711"), "twin")
        val graph = IndexedGraph(triples)
        assertTrue(triples.count { it.obj is BlankNode } >= 300_000, "the graph has about 300,000 blank nodes")

        val requested = setOf(BlankNode("r4711"), BlankNode("w4711"))
        val stats = BlankNodeKeys.Stats()
        val keys = BlankNodeKeys.compute(graph, requested, stats = stats)

        assertEquals(requested, keys.keys)
        assertEquals(2, keys.values.toSet().size)
        assertTrue(stats.describedNodes in 6..16, "described ${stats.describedNodes} blank nodes for a component of 3 with one sibling")
        assertEquals(0, graph.fullReads, "the graph was read in full")
        // The numbers of the duplicates do not depend on which of them were asked for.
        val twins = BlankNodeKeys.compute(graph, setOf(BlankNode("r4711"), BlankNode("rtwin")))
        assertEquals(keys.getValue(BlankNode("r4711")), twins.getValue(BlankNode("r4711")))
        assertEquals(setOf("", "2"), twins.values.map { it.substringAfter('-', "") }.toSet(), twins.toString())
    }
}
