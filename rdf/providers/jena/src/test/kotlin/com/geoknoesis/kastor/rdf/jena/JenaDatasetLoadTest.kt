package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.ProviderCapabilities
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [JenaProvider.parseDataset] behaves the same on every load path and keeps its buffers bounded. */
class JenaDatasetLoadTest {
    /** Records the size of every batch added, and how much input had been read when it was added. */
    private class RecordingRepository(private val delegate: RdfRepository, private val transactional: Boolean = true) :
        RdfRepository by delegate {
        val batches = mutableListOf<Int>()
        val bytesReadAtBatch = mutableListOf<Long>()
        var input: CountingInputStream? = null

        private fun recording(graph: MutableRdfGraph): MutableRdfGraph = object : MutableRdfGraph by graph {
            override fun addTriples(triples: Collection<RdfTriple>) {
                batches.add(triples.size)
                bytesReadAtBatch.add(input?.count ?: -1)
                graph.addTriples(triples)
            }
        }

        override fun editDefaultGraph(): MutableRdfGraph = recording(delegate.editDefaultGraph())
        override fun editGraph(name: Iri): MutableRdfGraph = recording(delegate.editGraph(name))
        override fun transaction(operations: RdfRepository.() -> Unit) = delegate.transaction { operations(this@RecordingRepository) }
        override fun getCapabilities(): ProviderCapabilities =
            delegate.getCapabilities().copy(supportsTransactions = transactional)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    // `_:g` names one graph twice (the same label must map to the same graph); `_:h` is a second blank graph.
    private val blankGraphInputs = listOf(
        "TRIG" to """
            PREFIX ex: <http://example.org/>
            ex:g { ex:s ex:p ex:o . }
            _:g { ex:s ex:p ex:o2 . }
            _:h { ex:s ex:p ex:o3 . }
            _:g { ex:s ex:p ex:o4 . }
        """.trimIndent(),
        "N-QUADS" to """
            <http://example.org/s> <http://example.org/p> <http://example.org/o> <http://example.org/g> .
            <http://example.org/s> <http://example.org/p> <http://example.org/o2> _:g .
            <http://example.org/s> <http://example.org/p> <http://example.org/o3> _:h .
            <http://example.org/s> <http://example.org/p> <http://example.org/o4> _:g .
        """.trimIndent(),
    )

    private fun loadPaths(): List<Pair<String, () -> RdfRepository>> = listOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "jena-inference" to { JenaRepository.MemoryRepositoryWithInference() },
        "foreign transactional" to { RecordingRepository(Rdf.memory()) },
        "foreign non-transactional" to { RecordingRepository(Rdf.memory(), transactional = false) },
    )

    @Test
    fun `blank-node graph names are skolemized the same way on every load path`() {
        val ex = "http://example.org/"
        fun t(o: String) = RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Iri(ex + o))
        for ((path, create) in loadPaths()) {
            for ((format, data) in blankGraphInputs) {
                val skolemGraphs = create().use { repo ->
                    JenaProvider().parseDataset(repo, data.byteInputStream(), format)
                    val graphs = repo.listGraphs()
                    assertTrue(repo.getGraph(Iri(ex + "g")).hasTriple(t("o")), "$path $format")
                    val skolem = graphs.filter { it.value.startsWith(JenaParsing.SKOLEM_GRAPH_PREFIX) }
                    assertEquals(3, graphs.size, "$path $format: $graphs")
                    assertEquals(2, skolem.size, "$path $format: one graph per distinct blank label: $graphs")
                    val g = skolem.single { repo.getGraph(it).hasTriple(t("o2")) }
                    assertTrue(repo.getGraph(g).hasTriple(t("o4")), "$path $format: the same label names the same graph")
                    val h = skolem.single { it != g }
                    assertTrue(repo.getGraph(h).hasTriple(t("o3")), "$path $format")
                    skolem.toSet()
                }
                val again = create().use { repo ->
                    JenaProvider().parseDataset(repo, data.byteInputStream(), format)
                    repo.listGraphs().filter { it.value.startsWith(JenaParsing.SKOLEM_GRAPH_PREFIX) }.toSet()
                }
                assertTrue((skolemGraphs intersect again).isEmpty(), "$path $format: blank graph names are scoped to one load")
            }
        }
    }

    @Test
    fun `a skolem graph name is derived from the blank node label so terms stay linked to their graph`() {
        val ex = "http://example.org/"
        val data = mapOf(
            "TRIG" to "PREFIX ex: <$ex>\n_:g { _:g ex:p ex:o . ex:s ex:q _:g . _:other ex:p ex:o . }\n",
            "N-QUADS" to "_:g <${ex}p> <${ex}o> _:g .\n<${ex}s> <${ex}q> _:g _:g .\n_:other <${ex}p> <${ex}o> _:g .\n",
        )
        val form = Regex(Regex.escape(JenaParsing.SKOLEM_GRAPH_PREFIX) + "[0-9a-f]{32}:[A-Za-z0-9._%-]+")
        for ((path, create) in loadPaths()) {
            for ((format, text) in data) {
                create().use { repo ->
                    JenaProvider().parseDataset(repo, text.byteInputStream(), format)
                    val graph = repo.listGraphs().single()
                    assertTrue(form.matches(graph.value), "$path $format: ${graph.value}")
                    // (inference repositories add entailments; only the loaded statements matter here)
                    val triples = repo.getGraph(graph).getTriples().filter { it.predicate == Iri(ex + "p") || it.predicate == Iri(ex + "q") }
                    val self = triples.single { it.predicate == Iri(ex + "p") && it.subject == triples.single { t -> t.predicate == Iri(ex + "q") }.obj }
                    val label = (self.subject as com.geoknoesis.kastor.rdf.BlankNode).id
                    assertEquals(JenaParsing.blankNodeIdOfSkolemGraph(graph.value), label, "$path $format: the graph name carries the blank node id")
                    assertEquals(3, triples.size, "$path $format")
                    assertEquals(2, triples.flatMap { listOf(it.subject, it.obj) }.filterIsInstance<com.geoknoesis.kastor.rdf.BlankNode>().distinct().size, "$path $format")
                }
            }
        }
    }

    @Test
    fun `skolemizing needs no per-load state and separate loads never share a graph`() {
        // The name is a function of the load and the label alone, so the sink keeps nothing per graph name.
        val collected = ArrayList<org.apache.jena.graph.Node>()
        fun sink() = JenaParsing.validatingDataset(object : org.apache.jena.riot.system.StreamRDFBase() {
            override fun quad(quad: org.apache.jena.sparql.core.Quad) { collected.add(quad.graph) }
        })
        val s = org.apache.jena.graph.NodeFactory.createURI("http://example.org/s")
        fun quad(label: String) = org.apache.jena.sparql.core.Quad.create(org.apache.jena.graph.NodeFactory.createBlankNode(label), s, s, s)
        val first = sink()
        first.quad(quad("a")); first.quad(quad("b")); first.quad(quad("a"))
        assertEquals(collected[0], collected[2], "the same label names the same graph within a load")
        assertTrue(collected[0] != collected[1])
        assertTrue(collected.all { it.isURI })
        val second = sink()
        second.quad(quad("a"))
        assertTrue(collected[3] != collected[0], "another load of the same document creates new graphs")
        assertEquals("a", JenaParsing.blankNodeIdOfSkolemGraph(collected[3].uri))
        // Labels are escaped so the name is always a valid IRI, and the escape is reversible.
        val odd = sink()
        odd.quad(quad("-7f:1 b/%"))
        com.geoknoesis.kastor.rdf.Iri(collected[4].uri)
        assertEquals("-7f:1 b/%", JenaParsing.blankNodeIdOfSkolemGraph(collected[4].uri))
    }

    @Test
    fun `the default graph block and IRI-named graphs still load on every path`() {
        val data = "PREFIX ex: <http://example.org/>\n{ ex:s ex:p ex:o . }\nex:g { ex:s ex:p ex:o2 . }\n"
        for ((path, create) in loadPaths()) {
            create().use { repo ->
                JenaProvider().parseDataset(repo, data.byteInputStream(), "TRIG")
                assertTrue(repo.defaultGraph.size() >= 1, path)
                assertTrue(repo.getGraph(Iri("http://example.org/g")).size() >= 1, path)
            }
        }
    }

    @Test
    fun `N-Quads with many small graphs is flushed before the whole input is buffered`() {
        val graphs = 50_000
        val bytes = buildString {
            for (i in 0 until graphs) appendLine("<http://example.org/s$i> <http://example.org/p> \"$i\" <http://example.org/g$i> .")
        }.toByteArray()
        Rdf.memory().use { memory ->
            val recording = RecordingRepository(memory)
            val input = CountingInputStream(ByteArrayInputStream(bytes))
            recording.input = input
            JenaProvider().parseDataset(recording, input, "N-QUADS")
            assertEquals(graphs, recording.batches.sum())
            assertEquals(graphs, recording.listGraphs().size)
            val first = recording.bytesReadAtBatch.first()
            assertTrue(first < bytes.size / 2, "buffers must be flushed while parsing: first flush after $first of ${bytes.size} bytes")
        }
    }
}
