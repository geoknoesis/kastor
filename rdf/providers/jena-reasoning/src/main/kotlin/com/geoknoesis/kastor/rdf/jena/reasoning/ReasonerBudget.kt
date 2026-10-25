package com.geoknoesis.kastor.rdf.jena.reasoning

/**
 * The nanoseconds of [this] budget, at most a quarter of the `Long` range (about 73 years: no limit in practice), so
 * that `start + budget` and the differences taken from it never overflow, however long the configured timeout
 * (`Duration.ofMillis(Long.MAX_VALUE)` is a common way to say "no limit", and `toNanos()` throws for it).
 */
internal fun java.time.Duration.budgetNanos(): Long = try {
    toNanos().coerceAtMost(Long.MAX_VALUE / 4)
} catch (e: ArithmeticException) {
    Long.MAX_VALUE / 4
}
