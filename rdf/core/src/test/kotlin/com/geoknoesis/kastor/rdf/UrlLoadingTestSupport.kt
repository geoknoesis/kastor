package com.geoknoesis.kastor.rdf

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One loopback HTTP server for a whole test class: each test installs its handler with [serve], which also starts a
 * new request log. Close it when the class is done.
 */
internal class LoopbackHttp(threads: Int = 8) : AutoCloseable {
    private val workers = Executors.newFixedThreadPool(threads) { Thread(it, "loopback-http").apply { isDaemon = true } }

    @Volatile private var handler: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }

    /** Raw request targets (path and query, as sent) since the last [serve], in arrival order. */
    @Volatile var requests: MutableList<String> = CopyOnWriteArrayList()
        private set

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val current = handler
            requests.add(exchange.requestURI.toString())
            try {
                current(exchange)
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                exchange.close()
            }
        }
        executor = workers
        start()
    }

    val port: Int = server.address.port

    /** The root URL without a trailing slash, e.g. `http://127.0.0.1:1234`. */
    val root: String = "http://127.0.0.1:$port"

    /** Installs [handler] for the requests from now on, starts a new request log, and returns [root]. */
    fun serve(handler: (HttpExchange) -> Unit): String {
        requests = CopyOnWriteArrayList()
        this.handler = handler
        return root
    }

    override fun close() {
        server.stop(0)
        workers.shutdownNow()
    }
}

internal fun HttpExchange.redirect(location: String, status: Int = 302) {
    responseHeaders.add("Location", location)
    sendResponseHeaders(status, -1)
}

internal fun HttpExchange.reply(status: Int, body: String, contentType: String = "text/turtle") {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.write(bytes)
}

internal fun HttpExchange.turtle(body: String = "<#s> <urn:p> <> .") = reply(200, body)

/**
 * A host name lookup that needs no resolver: the names in [hosts] have the addresses given there, an IP literal is
 * itself, and every other name is unknown. [lookups] records the names asked for.
 */
internal class FakeResolver(private val hosts: Map<String, List<String>>) : (String) -> Array<InetAddress> {
    val lookups = CopyOnWriteArrayList<String>()

    override fun invoke(host: String): Array<InetAddress> {
        lookups.add(host)
        val literals = hosts[host.lowercase()]
            ?: listOf(host).takeIf { host.contains(':') || host.none(Char::isLetter) }
            ?: throw UnknownHostException(host)
        // An address literal is parsed, not looked up.
        return literals.map { InetAddress.getByName(it) }.toTypedArray()
    }
}

/**
 * An HTTP connection that never touches the network: [status] plays the server up to the response headers (it may
 * block, like a slow server), and [body] opens the response body. Counts how often it was disconnected.
 */
internal class FakeHttpConnection(
    url: URL,
    private val status: (FakeHttpConnection) -> Int = { 200 },
    private val body: (FakeHttpConnection) -> InputStream = { InputStream.nullInputStream() },
) : HttpURLConnection(url) {
    val disconnects = AtomicInteger()
    val connects = AtomicInteger()

    override fun connect() {
        connects.incrementAndGet()
        connected = true
    }

    override fun getResponseCode(): Int = status(this)
    override fun getInputStream(): InputStream = body(this)
    override fun getErrorStream(): InputStream? = null
    override fun getHeaderField(name: String?): String? = null
    override fun getContentLengthLong(): Long = -1
    override fun usingProxy(): Boolean = false

    override fun disconnect() {
        disconnects.incrementAndGet()
    }
}

/** A connection of a scheme other than HTTP that never touches the network: [onConnect] and [body] may block. */
internal class FakeUrlConnection(
    url: URL,
    private val onConnect: (FakeUrlConnection) -> Unit = {},
    private val body: (FakeUrlConnection) -> InputStream,
) : URLConnection(url) {
    override fun connect() {
        if (connected) return
        onConnect(this)
        connected = true
    }

    override fun getInputStream(): InputStream = body(this)
    override fun getContentLengthLong(): Long = -1
}

/**
 * Waits for [latch] the way a blocked socket operation of the JDK waits: an interrupt does not end it. Returns the
 * number of interrupts it ignored. The wait gives up after [limitSeconds], so that a deadline that is not kept fails
 * a test instead of hanging it.
 */
internal fun awaitIgnoringInterrupts(latch: CountDownLatch, limitSeconds: Long = 120): Int {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(limitSeconds)
    var interrupts = 0
    while (true) {
        try {
            latch.await(end - System.nanoTime(), TimeUnit.NANOSECONDS)
            return interrupts
        } catch (_: InterruptedException) {
            interrupts++
        }
    }
}
