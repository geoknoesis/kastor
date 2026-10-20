package com.geoknoesis.kastor.gen.runtime

/**
 * Lifetime of one cached generation. The entry monitor protects selection and replacement; this monitor protects
 * readers after they leave the entry monitor. The caller releases a returned value outside this monitor.
 */
internal class CachedGraphState<S : Any>(val value: S) {
    private var readers = 0
    private var retired = false

    @Synchronized
    fun retain() {
        check(!retired) { "Cannot retain a retired graph state" }
        readers++
    }

    @Synchronized
    fun relinquish(): S? {
        check(readers > 0) { "No graph state reader to release" }
        readers--
        return value.takeIf { retired && readers == 0 }
    }

    @Synchronized
    fun retire(): S? {
        check(!retired) { "Graph state already retired" }
        retired = true
        return value.takeIf { readers == 0 }
    }
}
