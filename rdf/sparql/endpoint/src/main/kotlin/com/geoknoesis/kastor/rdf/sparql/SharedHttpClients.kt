package com.geoknoesis.kastor.rdf.sparql

import java.net.http.HttpClient
import java.time.Duration

/**
 * One [HttpClient] (connection pool and selector thread) per connect timeout, shared by the open
 * repositories that use it and shut down when the last of them is closed.
 */
internal object SharedHttpClients {
    private class Entry(val client: HttpClient) {
        var references = 0
    }

    private val entries = HashMap<Duration, Entry>()

    @Synchronized
    fun acquire(connectTimeout: Duration): HttpClient {
        val entry = entries.getOrPut(connectTimeout) {
            Entry(
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    // A timeout too long to count is no timeout: the JDK client fails on such a value.
                    .apply { if (!Durations.isUnbounded(connectTimeout)) connectTimeout(connectTimeout) }
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            )
        }
        entry.references++
        return entry.client
    }

    @Synchronized
    fun release(connectTimeout: Duration) {
        val entry = entries[connectTimeout] ?: return
        if (--entry.references == 0) {
            entries.remove(connectTimeout)
            entry.client.shutdown()
        }
    }

    @Synchronized
    fun size(): Int = entries.size
}
