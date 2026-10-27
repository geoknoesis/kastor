package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.atlas.lib.Cache
import org.apache.jena.graph.Node
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.expr.Expr
import org.apache.jena.sparql.expr.ExprVar
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionEnv

/**
 * Jena's process-wide `NodeValue` cache, kept free of the large literals of sandboxed queries.
 *
 * Jena turns a node into a `NodeValue` (reading a variable, building the result of `CONCAT`, `REPLACE`, ...) through
 * one cache of 10,000 entries that every query of the process shares, keyed by the node. It kept the large strings a
 * sandboxed query computed (up to 16 Mi characters each) after the query ended, outside its memory budget: a few
 * queries filled the heap (measured: 200 MB left of a 256 MiB heap once the queries had failed and their repositories
 * were closed). A sandboxed query removes every literal longer than [MAX_CACHED_LENGTH] that it reads or computes
 * from the cache ([forget]), so the cache holds at most 10,000 small entries of sandboxed queries. Removing an entry
 * only costs another query a conversion.
 */
internal object JenaNodeValueCache {

    /** Longest lexical form of a literal that a sandboxed query leaves in Jena's `NodeValue` cache. */
    const val MAX_CACHED_LENGTH: Int = 1024

    @Suppress("UNCHECKED_CAST")
    private val cache: Cache<Node, NodeValue>? = try {
        NodeValue::class.java.getDeclaredField("nodeValueCache").apply { isAccessible = true }.get(null) as? Cache<Node, NodeValue>
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** Whether the cache was found (a Jena version without it, or one that hides it, needs no clean-up or cannot have it). */
    val available: Boolean get() = cache != null

    /** Removes [value]'s node from the cache when it is a literal longer than [MAX_CACHED_LENGTH]. */
    fun forget(value: NodeValue) {
        val cache = cache ?: return
        val node = value.asNode()
        if (node.isLiteral && node.literalLexicalForm.length > MAX_CACHED_LENGTH) cache.remove(node)
    }
}

/** A variable of a sandboxed expression: its value is read as Jena reads it, and then [JenaNodeValueCache.forget]s. */
internal class UncachedVar(v: Var) : ExprVar(v) {

    override fun eval(binding: Binding, env: FunctionEnv?): NodeValue = super.eval(binding, env).also(JenaNodeValueCache::forget)

    override fun copy(v: Var): Expr = UncachedVar(v)
}
