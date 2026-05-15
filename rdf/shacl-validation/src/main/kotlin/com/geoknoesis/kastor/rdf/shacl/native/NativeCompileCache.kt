package com.geoknoesis.kastor.rdf.shacl.native

import java.util.concurrent.ConcurrentHashMap
import com.geoknoesis.kastor.rdf.shacl.StaleShapesGraphTagException

internal object NativeCompileCache {
    private val compiled = ConcurrentHashMap<String, CompiledShapeGraph>()
    private val tagToDigest = ConcurrentHashMap<String, String>()

    fun assertTagOrRecord(tag: String, digest: String) {
        val existing = tagToDigest.putIfAbsent(tag, digest)
        if (existing != null && existing != digest) {
            throw StaleShapesGraphTagException(
                "Compile-cache tag '$tag' was bound to digest $existing but shapes graph now hashes to $digest",
            )
        }
    }

    fun getCompiled(cacheKey: String): CompiledShapeGraph? = compiled[cacheKey]

    fun putCompiled(cacheKey: String, graph: CompiledShapeGraph) {
        compiled[cacheKey] = graph
    }
}
