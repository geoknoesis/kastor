package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.StaleShapesGraphTagException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bounded cache owned by one validator; version tags cannot collide across callers.
 *
 * Compilation runs **outside** the cache lock: each key maps to a future completed by the first caller, while
 * concurrent callers for the same key wait on that future within their own [ValidationBudget]. Different keys
 * compile in parallel. A failed or cancelled compilation is removed so later callers retry. Callers already waiting
 * on a compilation that fails with a deterministic [ShapeCompileException] receive that same exception instead of
 * recompiling the same broken shapes graph; other failures (e.g. the owner's own deadline) make waiters retry.
 *
 * Capacity eviction only ever drops a **completed** compilation: an in-flight entry stays, so callers arriving
 * during the compilation still join it rather than compiling the same graph again (the cache may exceed its
 * capacity by the number of in-flight compilations). Tags are tiny and detect a changed shapes graph for the
 * validator's lifetime, so they have their own, much larger bound [tagCapacity]; a tag evicted beyond it can no
 * longer be checked.
 */
internal class NativeCompileCache(
    private val capacity: Int = 64,
    private val tagCapacity: Int = maxOf(capacity, DEFAULT_TAG_CAPACITY),
) {
    private val lock = Any()
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L
    init { require(capacity > 0) }
    private val compiled = LinkedHashMap<String, CompletableFuture<CompiledShapeGraph>>(16, 0.75f, true)
    private val tags = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > tagCapacity
    }

    fun assertTagOrRecord(tag: String, digest: String, budget: ValidationBudget = ValidationBudget.NONE) {
        budget.check("shape cache")
        synchronized(lock) {
            val existing = tags[tag]
            if (existing != null && existing != digest) throw StaleShapesGraphTagException("Shapes tag '$tag' changed within this validator")
            tags[tag] = digest
        }
    }

    fun getOrCompile(key: String, budget: ValidationBudget = ValidationBudget.NONE, compile: () -> CompiledShapeGraph): CompiledShapeGraph {
        while (true) {
            budget.check("shape cache")
            var owner = false
            val future = synchronized(lock) {
                val existing = compiled[key]
                if (existing != null) {
                    hits++
                    existing
                } else {
                    misses++
                    val created = CompletableFuture<CompiledShapeGraph>()
                    compiled[key] = created
                    trim(keep = key)
                    owner = true
                    created
                }
            }
            if (owner) {
                try {
                    val value = compile()
                    future.complete(value)
                    synchronized(lock) { trim(keep = key) }
                    return value
                } catch (t: Throwable) {
                    synchronized(lock) { if (compiled[key] === future) compiled.remove(key) }
                    future.completeExceptionally(t)
                    throw t
                }
            }
            try {
                return future.get(budget.remainingNanos(), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                budget.check("shape cache wait")
            } catch (e: ExecutionException) {
                // Deterministic compile errors are shared with every caller waiting on this attempt.
                (e.cause as? ShapeCompileException)?.let { throw it }
                // The owning caller failed for its own reasons (e.g. its deadline); retry, possibly becoming the owner.
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                budget.check("shape cache wait")
                throw e
            }
        }
    }

    /** Evicts least recently used **completed** entries (never [keep] or an in-flight one) down to [capacity]. */
    private fun trim(keep: String) {
        if (compiled.size <= capacity) return
        val it = compiled.entries.iterator()
        while (compiled.size > capacity && it.hasNext()) {
            val e = it.next()
            if (e.key != keep && e.value.isDone) {
                it.remove()
                evictions++
            }
        }
    }

    fun statistics() = synchronized(lock) {
        com.geoknoesis.kastor.rdf.shacl.ShapeCacheStatistics(compiled.size, tags.size, hits, misses, evictions)
    }

    fun clear() = synchronized(lock) { compiled.clear(); tags.clear(); hits = 0; misses = 0; evictions = 0 }

    private companion object {
        const val DEFAULT_TAG_CAPACITY = 4096
    }
}
