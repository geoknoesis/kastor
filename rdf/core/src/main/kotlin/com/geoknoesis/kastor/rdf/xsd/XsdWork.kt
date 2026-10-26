package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi
import java.util.concurrent.atomic.AtomicInteger

/**
 * A work meter for the XSD lexical code of this package, for tests: every scan, comparison and conversion charges the
 * characters it reads, so a test can check that handling a multi-megabyte lexical form costs work linear in its
 * length, without measuring time. Metering is off (one volatile read per charge) unless a [measure] is running.
 */
@InternalKastorApi
object XsdWork {
    private val active = AtomicInteger()
    private val counter = ThreadLocal<LongArray?>()

    /** Runs [block] and returns its result with the work it charged on this thread. */
    fun <T> measure(block: () -> T): Pair<T, Long> {
        val previous = counter.get()
        val cell = LongArray(1)
        counter.set(cell)
        active.incrementAndGet()
        try {
            return block() to cell[0]
        } finally {
            active.decrementAndGet()
            counter.set(previous)
            previous?.let { it[0] += cell[0] }
        }
    }

    /** Charges [units] of work (characters read) to the running [measure] of this thread, if any. */
    internal fun charge(units: Int) {
        if (active.get() == 0) return
        counter.get()?.let { it[0] += units }
    }
}
