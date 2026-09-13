package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.StaleShapesGraphTagException
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** Bounded cache owned by one validator; version tags cannot collide across callers. */
internal class NativeCompileCache(private val capacity: Int = 64) {
    private val lock = ReentrantLock()
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L
    init { require(capacity > 0) }
    private val compiled = object : LinkedHashMap<String, CompiledShapeGraph>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompiledShapeGraph>?): Boolean = size > capacity
    }
    private val tags = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > capacity
    }
    fun assertTagOrRecord(tag: String, digest: String, budget: ValidationBudget = ValidationBudget.NONE) = guarded(budget) {
        val existing = tags[tag]
        if (existing != null && existing != digest) throw StaleShapesGraphTagException("Shapes tag '$tag' changed within this validator")
        tags[tag] = digest
    }
    fun getOrCompile(key: String, budget: ValidationBudget = ValidationBudget.NONE, compile: () -> CompiledShapeGraph): CompiledShapeGraph = guarded(budget) {
        compiled[key]?.let { hits++; return@guarded it }
        misses++
        val value = compile()
        if (compiled.size == capacity) evictions++
        compiled[key] = value
        value
    }
    fun statistics() = lock.withLock { com.geoknoesis.kastor.rdf.shacl.ShapeCacheStatistics(compiled.size, tags.size, hits, misses, evictions) }
    fun clear() = lock.withLock { compiled.clear(); tags.clear(); hits = 0; misses = 0; evictions = 0 }
    private fun <T> guarded(budget: ValidationBudget, action: () -> T): T {
        while (true) {
            budget.check("shape cache wait")
            try {
                if (lock.tryLock(10, TimeUnit.MILLISECONDS)) break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                budget.check("shape cache wait")
                throw e
            }
        }
        try { budget.check("shape cache"); return action() } finally { lock.unlock() }
    }
}
