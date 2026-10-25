package com.geoknoesis.kastor.rdf

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The URL loads behind [Rdf.parseFromUrl], [Rdf.parseFromUrlAsync] and [Rdf.parseDatasetFromUrl]: the request
 * itself is [openRdfUrlStream] (limits, policies, deadline), and the body goes to [RdfParsing].
 */
internal object RdfUrlParsing {
    /** Threads of the default [Rdf.parseFromUrlAsync] executor. */
    const val URL_IO_THREADS = 4

    /** Pending loads the default [Rdf.parseFromUrlAsync] executor queues before rejecting new ones. */
    const val URL_IO_QUEUE_CAPACITY = 256

    /**
     * Default executor for [Rdf.parseFromUrlAsync]: 4 daemon threads and a queue of 256 pending loads. When both are
     * full, a new load is rejected: its future completes exceptionally with [RejectedExecutionException]. A load
     * never runs on the calling thread.
     */
    val defaultExecutor: Executor by lazy { newUrlIoExecutor() }

    /**
     * An executor of the kind [Rdf.parseFromUrlAsync] uses by default: [threads] daemon threads that end when idle, a
     * queue of [queueCapacity] pending loads, and rejection (never the calling thread) beyond that. Tests create
     * small ones, so that saturating one takes a handful of loads.
     */
    fun newUrlIoExecutor(threads: Int = URL_IO_THREADS, queueCapacity: Int = URL_IO_QUEUE_CAPACITY): ThreadPoolExecutor =
        ThreadPoolExecutor(
            threads, threads, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue(queueCapacity),
            ThreadFactory { runnable -> Thread(runnable, "kastor-url-io").apply { isDaemon = true } },
            RejectedExecutionHandler { _, _ ->
                throw RejectedExecutionException(
                    "The default Rdf.parseFromUrlAsync executor is saturated ($threads loads running, " +
                        "$queueCapacity queued); retry later or pass an executor sized for this workload"
                )
            },
        ).apply { allowCoreThreadTimeOut(true) }

    fun graph(url: String, format: String, options: UrlLoadOptions): MutableRdfGraph {
        // An unknown format fails before the request is sent, not after the body was downloaded.
        val body = openRdfUrlStream(url, RdfFormat.fromStringOrThrow(format), options)
        return body.read { stream -> RdfParsing.graph(stream, format, body.baseIri) }
    }

    fun dataset(url: String, format: String, options: UrlLoadOptions): Dataset {
        val quads = RdfParsing.quadFormat(format, "parseFromUrl()")
        val body = openRdfUrlStream(url, quads, options)
        val repository = try {
            Rdf.memory()
        } catch (e: Throwable) {
            runCatching { body.stream.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        try {
            body.read { stream -> RdfParsing.dataset(repository, stream, format, body.baseIri) }
        } catch (e: Throwable) {
            runCatching { repository.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        return repository
    }

    /** [Rdf.parseFromUrlAsync] with the [UrlLoadRuntime] of the load (tests replace its connections and helper pool). */
    fun graphAsync(
        url: String,
        format: String,
        executor: Executor,
        options: UrlLoadOptions,
        runtime: UrlLoadRuntime = UrlLoadRuntime.DEFAULT,
    ): CompletableFuture<MutableRdfGraph> {
        val cancellation = UrlLoadCancellation(runtime.helpers)
        val result = object : CompletableFuture<MutableRdfGraph>() {
            // Never blocks: releasing the connection and the body happens on a helper thread (see UrlLoadCancellation).
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
                super.cancel(mayInterruptIfRunning).also { if (it) cancellation.cancel() }
        }
        val load = Runnable {
            if (result.isCancelled) return@Runnable
            try {
                // Checked on the thread that connects, right before connect(), under the lock cancel() takes:
                // a cancel that came first stops the request; one that comes later finds the connection.
                val body = openRdfUrlStream(url, RdfFormat.fromStringOrThrow(format), options, runtime, cancellation::connecting)
                body.read { stream ->
                    cancellation.reading(stream)
                    if (!result.isCancelled) result.complete(RdfParsing.graph(stream, format, body.baseIri))
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        try {
            executor.execute(load)
        } catch (e: RejectedExecutionException) {
            result.completeExceptionally(e)
        }
        return result
    }
}
