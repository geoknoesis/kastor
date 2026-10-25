package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UrlAddressPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * [SparqlEndpointConfig.redirectAddressPolicy]: a cross-origin redirect that is followed must still go to an address
 * the policy allows (public addresses by default), and the check cannot hold a call past its deadline.
 */
class SparqlRedirectAddressPolicyTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    /** A public endpoint that redirects every request to [location]; requests to any other host answer one row. */
    private fun redirectingTo(location: String, sent: MutableList<HttpRequest>) = HttpTransport { request ->
        sent += request
        val body = if (request.uri().host == "public.example") "" else rowsJson(1)
        val status = if (request.uri().host == "public.example") 307 else 200
        val headers = if (status == 307) mapOf("Location" to location) else mapOf("Content-Type" to "application/sparql-results+json")
        CompletableFuture.completedFuture(FakeResponse(request, status, body.byteInputStream(), headers))
    }

    @Test
    fun `a followed redirect to a metadata or loopback address is refused before anything is sent there`() {
        for (target in listOf(
            "http://169.254.169.254/latest/meta-data/",
            "http://127.0.0.1:8080/sparql",
            "http://[::1]/sparql",
            "http://10.0.0.5/sparql",
            "http://2130706433/sparql",
        )) {
            val sent = CopyOnWriteArrayList<HttpRequest>()
            val config = SparqlEndpointConfig("http://public.example/sparql", followCrossOriginRedirects = true)
            SparqlRepository(config, redirectingTo(target, sent)).use { repo ->
                val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                assertTrue(e.message!!.contains("redirectAddressPolicy"), e.message)
            }
            assertEquals(listOf("public.example"), sent.map { it.uri().host }, "nothing is sent to $target")
        }
    }

    @Test
    fun `an allowing policy lets the redirect through, and the policy sees the target`() {
        val asked = CopyOnWriteArrayList<URI>()
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val policy = UrlAddressPolicy { asked += it; true }
        val config = SparqlEndpointConfig("http://public.example/sparql", followCrossOriginRedirects = true, redirectAddressPolicy = policy)
        SparqlRepository(config, redirectingTo("http://10.0.0.5/sparql", sent)).use { repo ->
            assertEquals(1, repo.select(selectAll).count())
        }
        assertEquals(listOf(URI("http://10.0.0.5/sparql")), asked.toList())
        assertEquals(listOf("public.example", "10.0.0.5"), sent.map { it.uri().host })
    }

    @Test
    fun `same-origin redirects are not checked`() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val policy = UrlAddressPolicy { throw AssertionError("asked about a same-origin redirect: $it") }
        val transport = HttpTransport { request ->
            sent += request
            val moved = request.uri().path == "/sparql"
            CompletableFuture.completedFuture(
                FakeResponse(
                    request, if (moved) 307 else 200, (if (moved) "" else rowsJson(1)).byteInputStream(),
                    if (moved) mapOf("Location" to "/moved") else mapOf("Content-Type" to "application/sparql-results+json"),
                ),
            )
        }
        SparqlRepository(SparqlEndpointConfig("http://127.0.0.1:9/sparql", redirectAddressPolicy = policy), transport).use { repo ->
            assertEquals(1, repo.select(selectAll).count())
        }
        assertEquals(2, sent.size)
    }

    @Test
    fun `a policy that hangs ends the call at its header deadline`() {
        val release = CountDownLatch(1)
        val policy = UrlAddressPolicy {
            release.await()
            true
        }
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val config = SparqlEndpointConfig(
            "http://public.example/sparql",
            followCrossOriginRedirects = true,
            redirectAddressPolicy = policy,
            requestTimeout = Duration.ofMillis(300),
        )
        try {
            SparqlRepository(config, redirectingTo("http://other.example/sparql", sent)).use { repo ->
                val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                assertTrue(e.message!!.contains("deadline"), e.message)
            }
            assertEquals(1, sent.size, "the redirect target was never requested")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `the policy is a provider option`() {
        fun policy(value: String) =
            SparqlEndpointConfig.fromOptions(mapOf("location" to "https://h/sparql", "redirectAddressPolicy" to value)).redirectAddressPolicy
        assertEquals(UrlAddressPolicy.ALLOW_ALL, policy("allow_all"))
        assertEquals(UrlAddressPolicy.PUBLIC_ADDRESSES, policy("PUBLIC_ADDRESSES"))
        assertEquals(UrlAddressPolicy.PUBLIC_ADDRESSES, SparqlEndpointConfig("https://h/sparql").redirectAddressPolicy)
        assertThrows(IllegalArgumentException::class.java) { policy("none") }
    }
}
