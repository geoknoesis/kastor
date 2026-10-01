package com.geoknoesis.kastor.rdf.sparql

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * How late a timeout may fire before a test calls it "not enforced". Scheduling delays on a loaded
 * host only ever make a timeout fire late, so this is deliberately large; tests make every other
 * way for the call to end (another limit, the server giving up) take much longer than this.
 */
internal const val SLOW_HOST_SLACK_MILLIS = 15_000L

/** How long a test server stalls; closing the server interrupts the stall. */
internal const val STALL_MILLIS = 120_000L

internal fun elapsedMillisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

/**
 * Asserts that a call which started at [startNanos] ended because its [limit] fired: not before
 * the limit, and within [SLOW_HOST_SLACK_MILLIS] after it. Which limit fired is asserted by the
 * caller on the exception message.
 */
internal fun assertEndedAtLimit(limit: Duration, startNanos: Long, what: String) {
    val elapsed = elapsedMillisSince(startNanos)
    // Timers never fire early; 10% covers the difference between the caller's and the adapter's clock readings.
    assertTrue(elapsed >= limit.toMillis() * 9 / 10, "$what: ended after $elapsed ms, before its ${limit.toMillis()} ms limit")
    assertTrue(
        elapsed <= limit.toMillis() + SLOW_HOST_SLACK_MILLIS,
        "$what: ended after $elapsed ms, long after its ${limit.toMillis()} ms limit",
    )
}

internal class RecordedRequest(
    val method: String,
    val path: String,
    val contentType: String?,
    val headers: Map<String, String>,
    val rawQuery: String?,
    val body: String,
)

/** Local HTTP endpoint recording every request. */
internal class LocalEndpoint(private val handler: (HttpExchange, RecordedRequest) -> Unit) : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val pool = Executors.newFixedThreadPool(4)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/") { exchange ->
            try {
                val recorded = RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                    rawQuery = exchange.requestURI.rawQuery,
                    body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                )
                requests += recorded
                handler(exchange, recorded)
            } catch (_: Exception) {
                // Client disconnects and the interrupt that ends a stall surface here.
            } finally {
                exchange.close()
            }
        }
        start()
    }
    val port: Int get() = server.address.port
    val base: String get() = "http://127.0.0.1:$port"
    val url: String get() = "$base/sparql"
    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}

/** Sends [body] with [type] as Content-Type; `null` sends no Content-Type header at all. */
internal fun HttpExchange.respond(status: Int, body: String, type: String? = "application/sparql-results+json") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    if (type != null) responseHeaders.add("Content-Type", type)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
}

internal fun HttpExchange.redirect(status: Int, location: String?) {
    if (location != null) responseHeaders.add("Location", location)
    sendResponseHeaders(status, -1)
}

internal fun rowsJson(n: Int, value: (Int) -> String = { "row-$it" }): String = buildString {
    append("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[")
    repeat(n) {
        if (it > 0) append(',')
        append("{\"x\":{\"type\":\"literal\",\"value\":\"${value(it)}\"}}")
    }
    append("]}}")
}

/** Sends the response headers at once and then [rows] rows, pausing [gapMillis] after each. */
internal fun HttpExchange.trickleRows(rows: Int, gapMillis: Long) {
    responseHeaders.add("Content-Type", "application/sparql-results+json")
    sendResponseHeaders(200, 0)
    responseBody.use { out ->
        out.write("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[".toByteArray())
        repeat(rows) {
            if (it > 0) out.write(",".toByteArray())
            out.write("{\"x\":{\"type\":\"literal\",\"value\":\"$it\"}}".toByteArray())
            out.flush()
            Thread.sleep(gapMillis)
        }
        out.write("]}}".toByteArray())
    }
}

/** A server that accepts connections but never sends a response; [accepted] holds its connections. */
internal class SilentServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val accepted = CopyOnWriteArrayList<Socket>()
    val url: String get() = "http://127.0.0.1:${server.localPort}/sparql"

    init {
        Thread {
            try {
                while (true) accepted += server.accept()
            } catch (_: IOException) {
                // server closed
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
        accepted.forEach { it.close() }
    }
}
