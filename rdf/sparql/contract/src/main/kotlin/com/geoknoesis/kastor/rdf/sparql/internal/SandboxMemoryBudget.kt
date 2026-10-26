package com.geoknoesis.kastor.rdf.sparql.internal

import java.util.concurrent.atomic.AtomicLong

/**
 * **Not public API.** The memory budget of one sandboxed query execution: the characters (UTF-16 units) its
 * expressions computed, in total ([SandboxLimits.MAX_COMPUTED_CHARACTERS]). The per-value bounds of [SandboxLimits]
 * hold one value to 16 Mi chars, but many rows each holding such values would still exhaust the heap; this budget
 * holds the whole query.
 *
 * The accounting is cumulative, not live: a value counts when it is computed, even if the query drops it later. That
 * makes it deterministic (it does not depend on the garbage collector or on the provider's plan) and an upper bound of
 * what the query can retain. A string, an IRI or a blank node label counts its length; an integer or a decimal one
 * character per 16 bits beyond 64 (a value of a fixed-size type costs nothing); a triple term one per term. A provider
 * calls [require] before it builds a large value (`CONCAT`) and [charge] once it is computed. Shared by the threads of
 * one execution.
 */
class SandboxMemoryBudget(private val limit: Long = SandboxLimits.MAX_COMPUTED_CHARACTERS) {

    private val used = AtomicLong()

    /** The characters charged so far. */
    val charged: Long get() = used.get()

    /** Fails with [SandboxLimitException] when computing [size] more characters for [what] would exceed the budget. */
    fun require(size: Long, what: String) {
        val total = used.get() + size
        if (total > limit) throw exceeded(total, what)
    }

    /** Charges [size] characters that [what] computed; fails with [SandboxLimitException] past the budget. */
    fun charge(size: Long, what: String) {
        if (size <= 0) return
        val total = used.addAndGet(size)
        if (total > limit) throw exceeded(total, what)
    }

    private fun exceeded(total: Long, what: String) = SandboxLimitException(
        "$what would bring the characters the query computed to $total (the limit for one query is $limit)",
    )

    companion object {
        /** The characters an integer (or the unscaled value of a decimal) of [bits] bits is charged. */
        @JvmStatic
        fun numberSize(bits: Long): Long = if (bits <= 64) 0 else (bits - 64 + 15) / 16
    }
}
