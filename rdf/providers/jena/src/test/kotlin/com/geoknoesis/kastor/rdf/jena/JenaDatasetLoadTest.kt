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
}
