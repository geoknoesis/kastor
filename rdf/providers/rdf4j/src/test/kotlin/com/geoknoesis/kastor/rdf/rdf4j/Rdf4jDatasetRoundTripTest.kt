package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SerializationOptions
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `serializeDataset` writes what the graph API returns for every graph (one reifier per quoted triple, an explicit
 * `rdf:reifies` triple not duplicated by the implied one), so parsing the document into a fresh repository gives
 * graphs isomorphic to the original ones.
 */
class Rdf4jDatasetRoundTripTest {
    private val ex = "http://example.org/"
    private val provider = Rdf4jProvider()
    private val formats = listOf("TRIG", "N-QUADS")
    private val g1 = Iri(ex + "g1")
    private val g2 = Iri(ex + "g2")
    private val q = Iri(ex + "q")

    private fun quotedTerm(o: String) = TripleTerm(RdfTriple(Iri(ex + "a"), Iri(ex + "b"), Literal(o)))
    private fun reifierOf(term: TripleTerm) = Rdf4jTerms.reifierFor(Rdf4jTerms.toRdf4jValue(term) as org.eclipse.rdf4j.model.Triple)

    /** The Kastor view of a repository: graph name (empty for the default graph) to its triples. */
    private fun view(repo: Rdf4jRepository): Map<String, Set<RdfTriple>> {
        val out = LinkedHashMap<String, Set<RdfTriple>>()
        out[""] = repo.defaultGraph.getTriples().toSet()
        repo.listGraphs().sortedBy { it.value }.forEach { out[it.value] = repo.getGraph(it).getTriples().toSet() }
        return out.filterValues { it.isNotEmpty() }
    }

    private fun blankNodes(term: RdfTerm, out: MutableSet<BlankNode>) {
        when (term) {
            is BlankNode -> out.add(term)
            is TripleTerm -> { blankNodes(term.triple.subject, out); blankNodes(term.triple.obj, out) }
            else -> Unit
        }
    }

    private fun rename(term: RdfTerm, names: Map<BlankNode, BlankNode>): RdfTerm = when (term) {
        is BlankNode -> names.getValue(term)
        is TripleTerm -> TripleTerm(RdfTriple(rename(term.triple.subject, names) as RdfResource, term.triple.predicate, rename(term.triple.obj, names)))
        else -> term
    }

    /** Dataset isomorphism with one blank node bijection across all graphs (small data: tries the bijections). */
    private fun isomorphic(expected: Map<String, Set<RdfTriple>>, actual: Map<String, Set<RdfTriple>>): Boolean {
        if (expected.keys != actual.keys || expected.any { (name, triples) -> triples.size != actual.getValue(name).size }) return false
        val from = LinkedHashSet<BlankNode>()
        val to = LinkedHashSet<BlankNode>()
        expected.values.flatten().forEach { blankNodes(it.subject, from); blankNodes(it.obj, from) }
        actual.values.flatten().forEach { blankNodes(it.subject, to); blankNodes(it.obj, to) }
        if (from.size != to.size) return false
        check(from.size <= 7) { "too many blank nodes for this test helper" }
        val sources = from.toList()
        fun search(index: Int, names: Map<BlankNode, BlankNode>, free: Set<BlankNode>): Boolean {
            if (index == sources.size) {
                return expected.all { (name, triples) ->
                    triples.map { RdfTriple(rename(it.subject, names) as RdfResource, it.predicate, rename(it.obj, names)) }.toSet() == actual.getValue(name)
                }
            }
            return free.any { candidate -> search(index + 1, names + (sources[index] to candidate), free - candidate) }
        }
        return search(0, emptyMap(), to)
    }

    private fun roundTrip(format: String, original: Rdf4jRepository): Map<String, Set<RdfTriple>> {
        val document = provider.serializeDataset(original, format, SerializationOptions.DEFAULT)
        return Rdf4jRepository.MemoryRepository().use { copy ->
            provider.parseDataset(copy, document.byteInputStream(), format)
            val copied = view(copy)
            // A second round trip is stable too.
            val again = provider.serializeDataset(copy, format, SerializationOptions.DEFAULT)
            Rdf4jRepository.MemoryRepository().use { third ->
                provider.parseDataset(third, again.byteInputStream(), format)
                assertTrue(isomorphic(copied, view(third)), "$format: second round trip")
            }
            copied
        }
    }

    private class Case(val name: String, val populate: (Rdf4jRepository) -> Unit)

    private val small = quotedTerm("c")
    private val big = quotedTerm("x".repeat(1 shl 20))

    private fun cases(term: TripleTerm, label: String): List<Case> {
        val reifier = reifierOf(term)
        val annotation = RdfTriple(reifier, q, Literal("z"))
        val reifies = RdfTriple(reifier, RDF.reifies, term)
        val star = "<< <${ex}a> <${ex}b> \"${(term.triple.obj as Literal).lexical}\" >> <${ex}q> \"z\" ."
        return listOf(
            Case("$label: annotated triples (RDF-star statements)") { repo ->
                provider.parseDataset(repo, "$star\n<${ex}g1> { $star <${ex}s> <${ex}p> \"plain\"@en . }".byteInputStream(), "TRIG")
            },
            Case("$label: an explicit rdf:reifies next to the annotation that implies it") { repo ->
                repo.editDefaultGraph().addTriples(listOf(annotation, reifies))
                repo.editGraph(g1).addTriples(listOf(reifies, annotation, RdfTriple(reifier, Iri(ex + "q2"), Literal("w"))))
            },
            Case("$label: an explicit rdf:reifies alone") { repo ->
                repo.editDefaultGraph().addTriple(reifies)
                repo.editGraph(g1).addTriple(reifies)
            },
            Case("$label: a reifier that is also used as an object, in two graphs") { repo ->
                repo.editDefaultGraph().addTriples(listOf(annotation, reifies, RdfTriple(Iri(ex + "s"), Iri(ex + "about"), reifier)))
                repo.editGraph(g1).addTriple(RdfTriple(Iri(ex + "s"), Iri(ex + "about"), reifier))
                repo.editGraph(g2).addTriple(annotation)
            },
            Case("$label: statements about a reifier whose rdf:reifies triple was removed") { repo ->
                val graph = repo.editDefaultGraph()
                graph.addTriples(listOf(annotation, reifies))
                assertTrue(graph.removeTriple(reifies))
                assertFalse(graph.hasTriple(reifies))
            },
            Case("$label: a triple term object and an ordinary blank node") { repo ->
                repo.editGraph(g1).addTriples(
                    listOf(
                        RdfTriple(Iri(ex + "s"), Iri(ex + "says"), term),
                        RdfTriple(BlankNode("n"), Iri(ex + "says"), term),
                        RdfTriple(BlankNode("n"), q, BlankNode("m")),
                        annotation,
                    ),
                )
            },
        )
    }

    /**
     * A blank node (or reifier) that occurs inside a triple term and outside of it. Rio writes a triple term as an
     * `urn:rdf4j:triple:` IRI and reads the blank node labels inside it as they are, while it renames the labels
     * outside of it; the parse paths relabel both alike, so the node stays one node.
     */
    private fun sharedNodeCases(term: TripleTerm, label: String): List<Case> {
        val n = BlankNode("n")
        val m = BlankNode("m")
        val says = Iri(ex + "says")
        val lexical = (term.triple.obj as Literal).lexical
        return listOf(
            Case("$label: a blank node inside and outside a triple term") { repo ->
                repo.editGraph(g1).addTriples(
                    listOf(
                        RdfTriple(n, says, TripleTerm(RdfTriple(n, q, m))),
                        RdfTriple(m, q, Literal(lexical)),
                        RdfTriple(Iri(ex + "s"), says, TripleTerm(RdfTriple(m, q, Literal("only inside and as a subject")))),
                    ),
                )
                repo.editDefaultGraph().addTriple(RdfTriple(n, q, Literal("the same node in another graph")))
            },
            Case("$label: an annotation on an annotation") { repo ->
                val star = "<< << <${ex}a> <${ex}b> \"$lexical\" >> <${ex}q> \"z\" >> <${ex}r> \"w\" ."
                provider.parseDataset(repo, "$star\n<${ex}g1> { $star }".byteInputStream(), "TRIG")
                // The inner reifier is the subject of its rdf:reifies triple and occurs inside the outer triple term.
                val view = repo.defaultGraph.getTriples()
                assertEquals(3, view.size, view.toString().take(500))
                assertEquals(2, view.count { it.predicate == RDF.reifies })
            },
            Case("$label: an annotation on an annotation, written through the graph API") { repo ->
                val inner = reifierOf(term)
                val outerTerm = TripleTerm(RdfTriple(inner, q, Literal("z")))
                val outer = BlankNode("outer")
                repo.editDefaultGraph().addTriples(
                    listOf(
                        RdfTriple(inner, RDF.reifies, term),
                        RdfTriple(outer, RDF.reifies, outerTerm),
                        RdfTriple(outer, Iri(ex + "r"), Literal("w")),
                        RdfTriple(Iri(ex + "s"), Iri(ex + "about"), outer),
                    ),
                )
            },
        )
    }

    private val allCases: List<Case> =
        cases(small, "small") + cases(big, "hashed reifier id") + sharedNodeCases(small, "small") + sharedNodeCases(big, "hashed reifier id")

    /** Serializes the default graph in a graph format and loads what a parser reads back into a fresh repository. */
    private fun graphRoundTrip(format: String, original: Rdf4jRepository, stream: Boolean): Set<RdfTriple> {
        val document = provider.serializeGraph(original.defaultGraph, format, SerializationOptions.DEFAULT)
        val parsed: List<RdfTriple> =
            if (stream) provider.openTripleStream(document.byteInputStream(), format).use { it.toList() }
            else provider.parseGraph(document.byteInputStream(), format).getTriples()
        return Rdf4jRepository.MemoryRepository().use { copy ->
            copy.editDefaultGraph().addTriples(parsed)
            copy.defaultGraph.getTriples().toSet()
        }
    }

    @TestFactory
    fun `a serialized graph parses back into an isomorphic graph`(): List<DynamicTest> = allCases.flatMap { case ->
        listOf("TURTLE", "N-TRIPLES").flatMap { format ->
            listOf(false, true).map { stream ->
                DynamicTest.dynamicTest("$format${if (stream) " (stream)" else ""}: ${case.name}") {
                    Rdf4jRepository.MemoryRepository().use { original ->
                        case.populate(original)
                        // The graph formats carry one graph: move everything into the default graph first.
                        original.listGraphs().forEach { name ->
                            original.editDefaultGraph().addTriples(original.getGraph(name).getTriples())
                            original.removeGraph(name)
                        }
                        val expected = original.defaultGraph.getTriples().toSet()
                        assertTrue(expected.isNotEmpty())
                        val actual = graphRoundTrip(format, original, stream)
                        assertTrue(isomorphic(mapOf("" to expected), mapOf("" to actual)), "expected $expected\nactual $actual".take(4000))
                    }
                }
            }
        }
    }

    @Test
    fun `blank node labels of separate documents never name one node`() {
        // Rio spells a triple term as an encoded IRI; the labels inside it are the ones written.
        val encoded = Rdf4jRepository.MemoryRepository().use { repo ->
            val n = BlankNode("n")
            repo.editDefaultGraph().addTriple(RdfTriple(n, Iri(ex + "says"), TripleTerm(RdfTriple(n, q, n))))
            provider.serializeGraph(repo.defaultGraph, "N-TRIPLES", SerializationOptions.DEFAULT)
        }
        assertTrue(encoded.contains("urn:rdf4j:triple:"), encoded)
        fun nodes(): Set<BlankNode> {
            val out = LinkedHashSet<BlankNode>()
            provider.parseGraph(encoded.byteInputStream(), "N-TRIPLES").getTriples().forEach { blankNodes(it.subject, out); blankNodes(it.obj, out) }
            return out
        }
        val first = nodes()
        val second = nodes()
        assertEquals(1, first.size, "one node inside and outside the triple term: $first")
        assertEquals(1, second.size)
        assertTrue((first intersect second).isEmpty(), "two parses share a blank node: $first")
        assertFalse(BlankNode("n") in first, "the label of the document is not the id of the node")
    }

    @TestFactory
    fun `a serialized dataset parses back into isomorphic graphs`(): List<DynamicTest> = allCases.flatMap { case ->
        formats.map { format ->
            DynamicTest.dynamicTest("$format: ${case.name}") {
                Rdf4jRepository.MemoryRepository().use { original ->
                    case.populate(original)
                    val expected = view(original)
                    assertTrue(expected.isNotEmpty())
                    val actual = roundTrip(format, original)
                    assertEquals(expected.mapValues { it.value.size }, actual.mapValues { it.value.size }, "graph sizes")
                    assertEquals(
                        expected.mapValues { (_, triples) -> triples.count { it.predicate == RDF.reifies } },
                        actual.mapValues { (_, triples) -> triples.count { it.predicate == RDF.reifies } },
                        "rdf:reifies triples per graph",
                    )
                    assertTrue(isomorphic(expected, actual), "expected $expected\nactual $actual".take(4000))
                }
            }
        }
    }

    @Test
    fun `blank node ids that are not plain labels stay distinct nodes with one label each`() {
        // Rio's N-Triples writer would write both `a-b` and `a2db` as `_:a2db`, and `a-b` inside a triple term as it is.
        val dashed = BlankNode("a-b")
        val hex = BlankNode("a2db")
        val p = Iri(ex + "p")
        val triples = setOf(
            RdfTriple(dashed, p, TripleTerm(RdfTriple(dashed, p, hex))),
            RdfTriple(hex, p, Literal("hex")),
            RdfTriple(BlankNode("Zebra"), p, BlankNode("9")),
        )
        for (format in listOf("N-TRIPLES", "TURTLE")) {
            Rdf4jRepository.MemoryRepository().use { repo ->
                repo.editDefaultGraph().addTriples(triples)
                val document = provider.serializeGraph(repo.defaultGraph, format, SerializationOptions.DEFAULT)
                val parsed = provider.parseGraph(document.byteInputStream(), format).getTriples().toSet()
                assertTrue(isomorphic(mapOf("" to triples), mapOf("" to parsed)), "$format: $document")
            }
        }
        for (format in formats) {
            Rdf4jRepository.MemoryRepository().use { repo ->
                repo.editGraph(g1).addTriples(triples)
                assertTrue(isomorphic(view(repo), roundTrip(format, repo)), format)
            }
        }
    }

    @Test
    fun `the isomorphism helper tells shared blank nodes from distinct ones`() {
        val p = Iri(ex + "p")
        fun graphs(first: String, second: String) =
            mapOf("" to setOf(RdfTriple(BlankNode(first), p, Literal("1"))), "g" to setOf(RdfTriple(BlankNode(second), p, Literal("2"))))
        assertTrue(isomorphic(graphs("a", "a"), graphs("x", "x")))
        assertTrue(isomorphic(graphs("a", "b"), graphs("x", "y")))
        assertFalse(isomorphic(graphs("a", "a"), graphs("x", "y")))
        assertFalse(isomorphic(graphs("a", "b"), graphs("x", "x")))
    }

    @Test
    fun `a dataset without RDF-star statements is written statement by statement`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("default")))
            repo.editGraph(g1).addTriple(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("named")))
            val nquads = provider.serializeDataset(repo, "N-QUADS", SerializationOptions.DEFAULT).lines().filter { it.isNotBlank() }.sorted()
            assertEquals(
                listOf("<${ex}s> <${ex}p> \"default\" .", "<${ex}s> <${ex}p> \"named\" <${ex}g1> ."),
                nquads,
            )
        }
    }

    @Test
    fun `explicit statements only are serialized on an inference repository`() {
        Rdf4jRepository.MemoryRdfsRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(Iri(ex + "s"), RDF.type, Iri(ex + "C")))
            val lines = provider.serializeDataset(repo, "N-QUADS", SerializationOptions.DEFAULT).lines().filter { it.isNotBlank() }
            assertEquals(1, lines.size, lines.toString())
        }
    }

    @Test
    fun `blank-node contexts of a wrapped store are still serialized`() {
        val vf = SimpleValueFactory.getInstance()
        val store = SailRepository(MemoryStore()).apply {
            init()
            connection.use { conn -> conn.add(vf.createIRI(ex + "s"), vf.createIRI(ex + "p"), vf.createLiteral("blank"), vf.createBNode("ctx")) }
        }
        Rdf4jRepository(store).use { repo ->
            val document = provider.serializeDataset(repo, "N-QUADS", SerializationOptions.DEFAULT)
            assertTrue(document.contains("\"blank\" _:"), document)
            // Loading it skolemizes the graph name, which makes the graph reachable.
            Rdf4jRepository.MemoryRepository().use { copy ->
                provider.parseDataset(copy, document.byteInputStream(), "N-QUADS")
                assertEquals(1, copy.getGraph(copy.listGraphs().single()).size())
            }
        }
    }
}
