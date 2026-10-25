package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Checks that need no response: they fail before a request is sent, or decide without throwing the wrong error. */
class UrlLoadingPreflightTest {
    @Test
    fun `an unknown format fails before the request is sent`() {
        LoopbackHttp(threads = 2).use { http ->
            val root = http.serve { it.turtle() }
            assertThrows(RdfFormatException::class.java) { Rdf.parseFromUrl("$root/doc.ttl", "TTL2", options = UrlLoadOptions.ALLOW_ALL_ADDRESSES) }
            val async = Rdf.parseFromUrlAsync("$root/doc.ttl", "TTL2", options = UrlLoadOptions.ALLOW_ALL_ADDRESSES)
            val error = assertThrows(ExecutionException::class.java) { async.get(60, TimeUnit.SECONDS) }
            assertTrue(error.cause is RdfFormatException, error.cause.toString())
            assertEquals(emptyList<String>(), http.requests.toList(), "no request for a format that cannot be parsed")
        }
    }

    @Test
    fun `SAME_HOST refuses a redirect from or to a URL without a host instead of failing`() {
        // An underscore makes java.net.URI read the authority as registry-based: URI.host is null.
        val hostless = java.net.URI("http://my_host.example/a")
        assertEquals(null, hostless.host)
        val same = UrlRedirectPolicy.SAME_HOST
        assertFalse(same.allows(hostless, java.net.URI("http://my_host.example/b")))
        assertFalse(same.allows(java.net.URI("http://example.org/a"), hostless))
        assertTrue(same.allows(java.net.URI("http://example.org/a"), java.net.URI("http://EXAMPLE.org/b")))
    }
}
