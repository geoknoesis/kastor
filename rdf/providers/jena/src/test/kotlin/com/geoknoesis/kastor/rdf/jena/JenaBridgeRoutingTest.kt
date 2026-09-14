package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.sun.net.httpserver.HttpServer
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Advertised formats work, and every JenaBridge entry point uses the strict, validating parser. */
class JenaBridgeRoutingTest {
    @TempDir
    lateinit var dir: Path

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")
    private val graph = MemoryGraph(listOf(RdfTriple(s, p, Iri("http://example.org/o"))))

    @Test
    fun `graphs serialize to the advertised quad formats as a default graph`() {
        val provider = JenaProvider()
        assertTrue("TRIG" in provider.getCapabilities().supportedOutputFormats)
        for (format in listOf("TRIG", "N-QUADS")) {
            val text = provider.serializeGraph(graph, format, com.geoknoesis.kastor.rdf.SerializationOptions.DEFAULT)
            JenaRepository.MemoryRepository().use { repo ->
                provider.parseDataset(repo, text.byteInputStream(), format)
                assertEquals(graph.getTriples(), repo.defaultGraph.getTriples(), format)
                assertTrue(repo.listGraphs().isEmpty(), format)
            }
        }
    }

    @Test
    fun `unsupported formats fail with RdfFormatException UnsupportedFormat`() {
        assertFailsWith<RdfFormatException.UnsupportedFormat> { JenaBridge.toString(graph, "NOT-A-FORMAT") }
        JenaRepository.MemoryRepository().use { repo ->
            assertFailsWith<RdfFormatException.UnsupportedFormat> { JenaProvider().serializeDataset(repo, "TURTLE", com.geoknoesis.kastor.rdf.SerializationOptions.DEFAULT) }
        }
        assertFailsWith<RdfFormatException.UnsupportedFormat> { JenaBridge.parseDatasetFromStream("".byteInputStream(), "TURTLE") }
    }

    @Test
    fun `fromFile resolves relative IRIs against the file and parses strictly`() {
        val good = dir.resolve("good.ttl").also { Files.writeString(it, "<s> <p> <o> .") }
        val loaded = JenaBridge.fromFile(good.toString())
        val subject = (loaded.getTriples().single().subject as Iri).value
        assertTrue(subject.startsWith("file:") && subject.endsWith("/${dir.fileName}/s"), subject)

        val badTag = dir.resolve("bad.nt").also { Files.writeString(it, "<http://e/s> <http://e/p> \"x\"@cantbethislong .") }
        assertFailsWith<RdfFormatException> { JenaBridge.fromFile(badTag.toString(), "N-TRIPLES") }
        val missingDot = dir.resolve("nodot.ttl").also { Files.writeString(it, "<http://e/s> <http://e/p> <http://e/o>") }
        assertFailsWith<RdfFormatException> { JenaBridge.fromFile(missingDot.toString()) }
    }

    @Test
    fun `fromUrl parses strictly with the URL as base`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/good") { exchange ->
            val body = "<s> <p> <o> .".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/bad") { exchange ->
            val body = "<http://e/s> <http://e/p> \"x\"^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#langString> .".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(Iri("$base/s"), JenaBridge.fromUrl("$base/good").getTriples().single().subject)
            assertFailsWith<RdfFormatException> { JenaBridge.fromUrl("$base/bad", "N-TRIPLES") }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `parseDatasetFromStream validates terms and maps syntax errors`() {
        val ok = JenaBridge.parseDatasetFromStream("<http://e/s> <http://e/p> <http://e/o> <http://e/g> .".byteInputStream(), "N-QUADS")
        assertEquals(1L, ok.getNamedModel("http://e/g").size())
        assertFailsWith<RdfFormatException> {
            JenaBridge.parseDatasetFromStream("<http://e/g> { <http://e/s> <http://e/p> \"x\"@en--unk }".byteInputStream(), "TRIG")
        }
        assertFailsWith<RdfFormatException> {
            JenaBridge.parseDatasetFromStream("<s> <http://e/p> <http://e/o> <http://e/g> .".byteInputStream(), "N-QUADS")
        }
    }

    @Test
    fun `parseDataset streams into the store and rolls back on a syntax error`() {
        listOf(JenaRepository.MemoryRepository(), JenaRepository.MemoryRepositoryWithInference()).forEach { repo ->
            repo.use {
                it.editDefaultGraph().addTriple(RdfTriple(s, p, Iri("http://example.org/kept")))
                val broken = "<http://e/g> { <http://e/a> <http://e/b> <http://e/c> . }\n<http://e/g> { <http://e/a> <http://e/b> }"
                assertFailsWith<RdfFormatException> { JenaProvider().parseDataset(it, broken.byteInputStream(), "TRIG") }
                assertTrue(it.listGraphs().isEmpty(), "no partial named graph")
                assertTrue(it.defaultGraph.hasTriple(RdfTriple(s, p, Iri("http://example.org/kept"))))

                JenaProvider().parseDataset(it, "<http://e/g> { <http://e/a> <http://e/b> <http://e/c> . }".byteInputStream(), "TRIG")
                assertEquals(listOf(Iri("http://e/g")), it.listGraphs())
            }
        }
    }

    @Test
    fun `wrapped foreign models read leniently by default and strictly on request`() {
        val model = ModelFactory.createDefaultModel()
        val subject = model.createResource("http://example.org/s")
        model.add(subject, model.createProperty("http://example.org/p"), model.createLiteral("hello", "en_US"))
        model.add(subject, model.createProperty("http://example.org/q"), "ok")

        val lenient = JenaBridge.fromJenaModel(model)
        assertEquals(listOf(Iri("http://example.org/q")), lenient.getTriples().map { it.predicate })
        assertEquals(1, lenient.find(subject = s).size)
        assertEquals(2, lenient.size(), "size counts every statement of the model")

        assertFailsWith<IllegalArgumentException> { JenaBridge.fromJenaModel(model, strictRead = true).getTriples() }
        assertEquals(1, model.graph.toKastorGraph().getTriples().size)
    }
}
