package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [UrlLoadOptions.addressPolicy]: asked about every URL of a load, the first one included, and the classification
 * behind [UrlAddressPolicy.PUBLIC_ADDRESSES], range by range, in every form an address can take.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UrlAddressPolicyTest {
    private val http = LoopbackHttp(threads = 4)
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    /** The request paths of the current test, in arrival order. */
    private val requests: MutableList<String> get() = http.requests

    @AfterEach
    fun cleanUp() {
        cleanups.reversed().forEach { runCatching(it) }
        cleanups.clear()
    }

    @AfterAll
    fun stopServer() {
        http.close()
    }

    /** Serves `/start` as a redirect to `/doc.ttl` and everything else as one triple; records the request paths. */
    private fun serve(): String = http.serve { exchange ->
        if (exchange.requestURI.path == "/start") exchange.redirect("/doc.ttl") else exchange.turtle("<urn:s> <urn:p> <urn:o> .")
    }

    // ---- the policy is applied to every URL of a load ----

    @Test
    fun `the address policy is asked about the first URL before anything is sent to it`() {
        val root = serve()
        val options = UrlLoadOptions(addressPolicy = UrlAddressPolicy.PUBLIC_ADDRESSES)
        val error = assertThrows(RdfAddressRefusedException::class.java) { Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE, options) }
        assertEquals("$root/doc.ttl", error.url)
        assertTrue(error.message!!.contains("UrlAddressPolicy.PUBLIC_ADDRESSES"), error.message)
        assertEquals(emptyList<String>(), requests.toList(), "no request may reach a refused address")

        // The same holds for the asynchronous and the dataset entry points.
        val async = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            Rdf.parseFromUrlAsync("$root/doc.ttl", RdfFormat.TURTLE, options = options).get(60, TimeUnit.SECONDS)
        }
        assertTrue(async.cause is RdfAddressRefusedException, async.cause.toString())
        assertThrows(RdfAddressRefusedException::class.java) { Rdf.parseDatasetFromUrl("$root/doc.nq", RdfFormat.N_QUADS, options) }
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `the address policy is asked about every redirect target as well`() {
        val root = serve()
        val seen = CopyOnWriteArrayList<String>()
        val allowing = UrlLoadOptions(addressPolicy = { url -> seen.add(url.toString()); true })
        assertEquals(1, Rdf.parseFromUrl("$root/start", RdfFormat.TURTLE, allowing).size())
        assertEquals(listOf("$root/start", "$root/doc.ttl"), seen.toList())

        requests.clear()
        val refusing = UrlLoadOptions(addressPolicy = { url -> !url.path.endsWith(".ttl") })
        val error = assertThrows(RdfAddressRefusedException::class.java) { Rdf.parseFromUrl("$root/start", RdfFormat.TURTLE, refusing) }
        assertEquals("$root/doc.ttl", error.url)
        assertTrue(error.message!!.contains("$root/start"), "the message names the URL the load started with: ${error.message}")
        assertEquals(listOf("/start"), requests.toList(), "the refused redirect target must not be requested")
    }

    @Test
    fun `the default allows every address and the redirect policy is asked before the address policy`() {
        assertEquals(UrlAddressPolicy.ALLOW_ALL, UrlLoadOptions.DEFAULT.addressPolicy)
        assertEquals(UrlAddressPolicy.ALLOW_ALL, UrlLoadOptions().addressPolicy)
        assertEquals("UrlAddressPolicy.ALLOW_ALL", UrlAddressPolicy.ALLOW_ALL.toString())
        assertEquals("UrlAddressPolicy.PUBLIC_ADDRESSES", UrlAddressPolicy.PUBLIC_ADDRESSES.toString())

        val root = serve()
        assertEquals(1, Rdf.parseFromUrl("$root/start").size())
        val order = CopyOnWriteArrayList<String>()
        val options = UrlLoadOptions(
            redirectPolicy = { _, to -> order.add("redirect ${to.path}"); true },
            addressPolicy = { url -> order.add("address ${url.path}"); true },
        )
        assertEquals(1, Rdf.parseFromUrl("$root/start", RdfFormat.TURTLE, options).size())
        assertEquals(listOf("address /start", "redirect /doc.ttl", "address /doc.ttl"), order.toList())
    }

    @Test
    fun `a custom address policy runs on a helper thread, within the deadline, and its failure fails the load`() {
        val root = serve()
        val threads = CopyOnWriteArrayList<String>()
        val recording = UrlLoadOptions(addressPolicy = { threads.add(Thread.currentThread().name); true })
        assertEquals(1, Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE, recording).size())
        assertTrue(threads.single().startsWith(UrlLoadHelpers.THREAD_NAME_PREFIX), threads.toString())

        // Without an overall deadline there is nothing to cut the policy off for: it runs on the calling thread.
        threads.clear()
        assertEquals(1, Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE, recording.copy(totalTimeoutMillis = 0)).size())
        assertEquals(listOf(Thread.currentThread().name), threads.toList())

        requests.clear()
        val release = CountDownLatch(1)
        cleanups.add { release.countDown() }
        val cutOff = CountDownLatch(1)
        val slow = UrlLoadOptions(
            totalTimeoutMillis = 400,
            addressPolicy = {
                try {
                    release.await(120, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    cutOff.countDown()
                    throw e
                }
                true
            },
        )
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE, slow) }
        }
        assertEquals(emptyList<String>(), requests.toList())
        // The policy call that was cut off is not left to run on: its helper thread is interrupted.
        assertTrue(cutOff.await(60, TimeUnit.SECONDS), "the abandoned policy call must be interrupted")

        val failing = UrlLoadOptions(addressPolicy = { throw IllegalStateException("policy failed") })
        val error = assertThrows(IllegalStateException::class.java) { Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE, failing) }
        assertEquals("policy failed", error.message)
    }

    // ---- public unicast classification ----

    /** Special-purpose IPv4 ranges (none of them public) with their first and last address and one in between. */
    private val specialIpv4 = linkedMapOf(
        "0.0.0.0/8 this network" to listOf("0.0.0.0", "0.1.2.3", "0.255.255.255"),
        "10.0.0.0/8 private" to listOf("10.0.0.0", "10.1.2.3", "10.255.255.255"),
        "100.64.0.0/10 shared address space" to listOf("100.64.0.0", "100.100.100.200", "100.127.255.255"),
        "127.0.0.0/8 loopback" to listOf("127.0.0.0", "127.0.0.1", "127.255.255.255"),
        "169.254.0.0/16 link-local" to listOf("169.254.0.0", "169.254.169.254", "169.254.255.255"),
        "172.16.0.0/12 private" to listOf("172.16.0.0", "172.20.1.1", "172.31.255.255"),
        "192.0.0.0/24 protocol assignments" to listOf("192.0.0.0", "192.0.0.8", "192.0.0.255"),
        "192.0.2.0/24 documentation" to listOf("192.0.2.0", "192.0.2.1", "192.0.2.255"),
        "192.88.99.0/24 6to4 relay" to listOf("192.88.99.0", "192.88.99.1", "192.88.99.255"),
        "192.168.0.0/16 private" to listOf("192.168.0.0", "192.168.1.1", "192.168.255.255"),
        "198.18.0.0/15 benchmarking" to listOf("198.18.0.0", "198.19.0.1", "198.19.255.255"),
        "198.51.100.0/24 documentation" to listOf("198.51.100.0", "198.51.100.7", "198.51.100.255"),
        "203.0.113.0/24 documentation" to listOf("203.0.113.0", "203.0.113.7", "203.0.113.255"),
        "224.0.0.0/4 multicast" to listOf("224.0.0.0", "224.0.0.251", "239.255.255.255"),
        "240.0.0.0/4 reserved" to listOf("240.0.0.0", "250.1.2.3", "255.255.255.254"),
        "255.255.255.255 broadcast" to listOf("255.255.255.255"),
    )

    /** Public addresses, among them the neighbours just outside every range above. */
    private val publicIpv4 = listOf(
        "1.0.0.0", "8.8.8.8", "9.255.255.255", "11.0.0.0", "93.184.216.34", "100.63.255.255", "100.128.0.0",
        "126.255.255.255", "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0",
        "192.0.1.0", "192.0.3.0", "192.88.98.255", "192.88.100.0", "192.167.255.255", "192.169.0.0",
        "198.17.255.255", "198.20.0.0", "198.51.99.255", "198.51.101.0", "203.0.112.255", "203.0.114.0",
        "223.255.255.255",
    )

    private fun bytes(literal: String): ByteArray = InetAddress.getByName(literal).address

    /** The IPv6 forms that carry the IPv4 address [ipv4], by name: IPv4-mapped, NAT64 and 6to4. */
    private fun carriers(ipv4: String): Map<String, ByteArray> {
        val v4 = bytes(ipv4)
        val mapped = ByteArray(16).also { it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte(); v4.copyInto(it, 12) }
        val nat64 = ByteArray(16).also { it[1] = 0x64; it[2] = 0xFF.toByte(); it[3] = 0x9B.toByte(); v4.copyInto(it, 12) }
        val sixToFour = ByteArray(16).also { it[0] = 0x20; it[1] = 0x02; v4.copyInto(it, 2); it[15] = 1 }
        return linkedMapOf("::ffff:$ipv4" to mapped, "64:ff9b::$ipv4" to nat64, "6to4 of $ipv4" to sixToFour)
    }

    @Test
    fun `every special-purpose IPv4 range is refused, in IPv4 form and carried in IPv6`() {
        for ((range, addresses) in specialIpv4) for (address in addresses) {
            assertFalse(isPublicUnicastAddress(bytes(address)), "$address ($range)")
            assertFalse(UrlAddressPolicy.isPublicUnicast(InetAddress.getByName(address)), "$address ($range)")
            assertFalse(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://$address/x")), "http://$address/x ($range)")
            for ((form, carrier) in carriers(address)) {
                assertFalse(isPublicUnicastAddress(carrier), "$form ($range)")
                assertFalse(UrlAddressPolicy.isPublicUnicast(InetAddress.getByAddress(carrier)), "$form ($range)")
            }
            // The textual forms, as they appear in a URL.
            for (literal in listOf("::ffff:$address", "64:ff9b::$address", "::$address")) {
                assertFalse(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://[$literal]/x")), "http://[$literal]/x ($range)")
            }
        }
    }

    @Test
    fun `public IPv4 addresses are allowed, in IPv4 form and carried in IPv6, but never as IPv4-compatible`() {
        for (address in publicIpv4) {
            assertTrue(isPublicUnicastAddress(bytes(address)), address)
            assertTrue(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://$address/x")), address)
            for ((form, carrier) in carriers(address)) assertTrue(isPublicUnicastAddress(carrier), form)
            assertTrue(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://[64:ff9b::$address]:8080/x")), address)
            // ::a.b.c.d is deprecated and not routable, whatever it carries.
            val compatible = ByteArray(16).also { bytes(address).copyInto(it, 12) }
            assertFalse(isPublicUnicastAddress(compatible), "::$address")
            assertFalse(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://[::$address]/x")), "::$address")
        }
    }

    @Test
    fun `IPv6 addresses are public only as global unicast outside the special prefixes`() {
        val refused = linkedMapOf(
            "::" to "unspecified", "::1" to "loopback",
            "64:ff9b:1::1" to "local-use NAT64", "64:ff9b:1:ffff::808:808" to "local-use NAT64",
            "64:ff9b:0:1::808:808" to "not the NAT64 /96",
            "100::1" to "discard-only", "100::ffff:ffff:ffff:ffff" to "discard-only",
            "2001::1" to "Teredo", "2001:0:4136:e378:8000:63bf:3fff:fdd2" to "Teredo", "2001:0:ffff:ffff::1" to "Teredo",
            "2001:2::1" to "benchmarking", "2001:10::1" to "ORCHID", "2001:1ff:ffff:ffff::1" to "end of 2001::/23",
            "2001:db8::" to "documentation", "2001:db8::1" to "documentation", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff" to "documentation",
            "2002:a00:1::1" to "6to4 of 10.0.0.1", "2002:7f00:1::1" to "6to4 of 127.0.0.1",
            "2002:6464:64c8::1" to "6to4 of 100.100.100.200", "2002:a9fe:a9fe::1" to "6to4 of 169.254.169.254",
            "3fff::1" to "documentation", "3fff:fff:ffff:ffff::1" to "documentation",
            "4000::1" to "outside 2000::/3", "5f00::1" to "segment routing SIDs", "8000::1" to "outside 2000::/3",
            "fc00::1" to "unique-local", "fd12:3456:789a::1" to "unique-local", "fdff:ffff::1" to "unique-local",
            "fe80::1" to "link-local", "febf::1" to "link-local", "fec0::1" to "site-local",
            "ff02::1" to "multicast", "ff0e::1" to "multicast",
            "::ffff:0:808:808" to "IPv4-translated", "0:0:0:1::1" to "reserved",
        )
        for ((address, why) in refused) {
            assertFalse(isPublicUnicastAddress(bytes(address)), "$address ($why)")
            assertFalse(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://[$address]/x")), "http://[$address]/x ($why)")
            assertFalse(UrlRedirectPolicy.PUBLIC_ADDRESSES.allows(URI("http://example.org/"), URI("http://[$address]/x")), address)
        }
        val public = listOf(
            "2000::1", "2001:200::1", "2001:4860:4860::8888", "2001:db7:ffff::1", "2001:db9::1", "2002:808:808::1",
            "2003::1", "2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "3ffe::1", "3fff:1000::1",
        )
        for (address in public) {
            assertTrue(isPublicUnicastAddress(bytes(address)), address)
            assertTrue(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("https://[$address]:8443/x")), address)
        }
        assertFalse(isPublicUnicastAddress(ByteArray(0)))
        assertFalse(isPublicUnicastAddress(ByteArray(8)))
    }

    @Test
    fun `host names, unusual literals and URLs without a host`() {
        // The policy of PUBLIC_ADDRESSES with a host name lookup of the test's: no resolver is asked, so the test
        // does not depend on the network, on the hosts file or on how long an unknown name takes to fail.
        val resolver = FakeResolver(
            mapOf(
                "localhost" to listOf("127.0.0.1"),
                "public.example" to listOf("93.184.216.34"),
                "two-homed.example" to listOf("93.184.216.34", "2606:2800:220:1::1"),
                "split.example" to listOf("93.184.216.34", "10.0.0.7"),
                "metadata.example" to listOf("169.254.169.254"),
            ),
        )
        val policy = UrlAddressPolicy.publicAddresses(resolver)
        // Names that resolve to loopback or to a link-local address, a name of which one address is private, forms
        // of 127.0.0.1 some resolvers accept, and names that do not resolve: none is a public address.
        val refused = listOf(
            "http://localhost/x", "http://LOCALHOST:8080/x", "http://2130706433/x", "http://127.1/x",
            "http://no-such-host.invalid/x", "http://split.example/x", "http://metadata.example/latest/meta-data",
            "file:///etc/passwd", "jar:file:/app.jar!/data.ttl", "urn:example:x", "http:///x",
        )
        for (url in refused) assertFalse(policy.allows(URI(url)), url)
        assertTrue(policy.allows(URI("http://public.example/x")))
        assertTrue(policy.allows(URI("https://two-homed.example:8443/x")))
        // The lookup is asked about host names, with the host as the URL has it; a URL without a host never gets
        // that far. (The numeric forms may or may not be hosts for java.net.URI: either way they are refused.)
        val names = resolver.lookups.filter { name -> name.any(Char::isLetter) }
        assertEquals(
            listOf("localhost", "LOCALHOST", "no-such-host.invalid", "split.example", "metadata.example", "public.example", "two-homed.example"),
            names,
        )
        // The public policy is that policy with the system's lookup.
        assertFalse(UrlAddressPolicy.PUBLIC_ADDRESSES.allows(URI("http://127.0.0.1/x")))
        // The redirect policy of the same name applies the same rules to the target.
        assertFalse(UrlRedirectPolicy.PUBLIC_ADDRESSES.allows(URI("http://example.org/"), URI("http://100.100.100.200/latest/meta-data")))
        assertTrue(UrlRedirectPolicy.PUBLIC_ADDRESSES.allows(URI("http://example.org/"), URI("http://93.184.216.34/x")))
    }
}
