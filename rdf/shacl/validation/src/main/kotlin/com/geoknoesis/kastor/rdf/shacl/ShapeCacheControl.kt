package com.geoknoesis.kastor.rdf.shacl

/** Optional cache management exposed by the native validator. Counts are cumulative until cleared. */
interface ShapeCacheControl {
    val cacheStatistics: ShapeCacheStatistics
    fun clearCache()
}

data class ShapeCacheStatistics(val entries: Int, val versionTags: Int, val hits: Long, val misses: Long, val evictions: Long)
