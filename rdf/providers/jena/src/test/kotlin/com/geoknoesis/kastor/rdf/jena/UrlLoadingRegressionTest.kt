package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals

/**
 * Loading a graph from a URL with the Jena provider on the classpath.
 *
 * The saturation behaviour of the default asynchronous loader (bounded workers, rejection beyond the queue) is a
 * property of `rdf-core` and is tested there with an injectable queue capacity; this module only checks that a URL
 * load is parsed correctly, with a handful of loopback connections.
 */
class UrlLoadingRegressionTest {
    @Test
    @Timeout(60)
    fun `a graph loaded from a URL is parsed by the Jena provider`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val data = "<urn:s> <urn:p> <urn:o> .".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/doc"
            val expected = RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o"))
            // Three loads, one after the other: each completes, none depends on another being in flight.
            repeat(3) {
                assertEquals(listOf(expected), Rdf.parseFromUrlAsync(url).get(30, TimeUnit.SECONDS).getTriples())
            }
            assertEquals(listOf(expected), JenaBridge.fromUrl(url).getTriples())
        } finally {
            server.stop(0)
        }
    }
}
