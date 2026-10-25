package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * URL loading refuses non-public addresses unless the caller opts in. Literal addresses only, so no name is looked up;
 * the connections are fakes, so nothing is sent anywhere.
 */
class UrlLoadingDefaultAddressPolicyTest {
    private val nonPublic = listOf(
        "http://127.0.0.1/doc.ttl",
        "http://10.1.2.3/doc.ttl",
        "http://169.254.169.254/latest/meta-data/",
        "http://[::1]/doc.ttl",
        "http://[fd12:3456:789a::1]/doc.ttl",
    )

    /** A runtime whose connections answer with one triple, recording every URL a connection was opened for. */
    private fun recordingRuntime(opened: MutableList<String>) = UrlLoadRuntime(open = { uri ->
        opened += uri.toString()
        object : java.net.HttpURLConnection(uri.toURL()) {
            override fun connect() { connected = true }
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode(): Int = 200
            override fun getInputStream(): java.io.InputStream = "<urn:s> <urn:p> <urn:o> .".byteInputStream()
        }
    })

    private fun load(url: String, options: UrlLoadOptions, runtime: UrlLoadRuntime): Int =
        openRdfUrlStream(url, RdfFormat.TURTLE, options, runtime).read { it.readBytes().size }

    @Test
    fun `the default options use the public-address policy`() {
        assertSame(UrlAddressPolicy.PUBLIC_ADDRESSES, UrlLoadOptions().addressPolicy)
        assertSame(UrlAddressPolicy.PUBLIC_ADDRESSES, UrlLoadOptions.DEFAULT.addressPolicy)
        assertSame(UrlAddressPolicy.ALLOW_ALL, UrlLoadOptions.ALLOW_ALL_ADDRESSES.addressPolicy)
    }

    @Test
    fun `by default loopback, private, link-local and unique-local addresses are refused before connecting`() {
        val opened = CopyOnWriteArrayList<String>()
        val runtime = recordingRuntime(opened)
        for (url in nonPublic) {
            assertThrows(RdfAddressRefusedException::class.java, { load(url, UrlLoadOptions.DEFAULT, runtime) }, url)
            assertThrows(RdfAddressRefusedException::class.java, { load(url, UrlLoadOptions(), runtime) }, url)
        }
        assertEquals(emptyList<String>(), opened.toList(), "no connection may be opened")
    }

    @Test
    fun `the public API refuses them by default too`() {
        for (url in nonPublic) {
            assertThrows(RdfAddressRefusedException::class.java, { Rdf.parseFromUrl(url) }, url)
        }
    }

    @Test
    fun `ALLOW_ALL_ADDRESSES opts in to every address`() {
        val opened = CopyOnWriteArrayList<String>()
        val runtime = recordingRuntime(opened)
        for (url in nonPublic) assertEquals(25, load(url, UrlLoadOptions.ALLOW_ALL_ADDRESSES, runtime), url)
        assertEquals(nonPublic, opened.toList())
    }

    @Test
    fun `a redirect to a non-public address is refused by default`() {
        val opened = CopyOnWriteArrayList<String>()
        val runtime = UrlLoadRuntime(open = { uri ->
            opened += uri.toString()
            object : java.net.HttpURLConnection(uri.toURL()) {
                override fun connect() { connected = true }
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode(): Int = 302
                override fun getHeaderField(name: String?): String? =
                    if (name.equals("Location", ignoreCase = true)) "http://169.254.169.254/latest/" else null
                override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            }
        })
        // A public literal address, so the first URL passes without a name lookup.
        assertThrows(RdfAddressRefusedException::class.java) { load("http://8.8.8.8/doc.ttl", UrlLoadOptions.DEFAULT, runtime) }
        assertEquals(listOf("http://8.8.8.8/doc.ttl"), opened.toList())
    }
}
