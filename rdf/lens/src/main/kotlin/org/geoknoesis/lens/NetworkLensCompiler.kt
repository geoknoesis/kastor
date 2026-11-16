package org.geoknoesis.lens

/** A bounded graph view with independent traversal and final-output filtering. */
data class NetworkLens(
    val seeds: Lens,
    val predicate: String,
    val maxDepth: Int,
    val direction: NetworkDirection = NetworkDirection.OUT,
    val traversalNode: Lens? = null,
    val outputNode: Lens? = null,
    val traversalEdge: List<EdgeCondition> = emptyList(),
    val outputEdge: List<EdgeCondition> = emptyList(),
    val closure: NetworkClosure = NetworkClosure.TRAVERSED,
    val datasetGraph: String? = null
) {
    init { require(maxDepth in 0..3) { "Portable NetworkLens requires a depth of 0..3" } }
}
enum class NetworkDirection { OUT, IN, BOTH }
enum class NetworkClosure { TRAVERSED, INDUCED }
data class EdgeCondition(val predicate: String, val value: Value)
data class CompiledNetwork(val nodes: String, val edges: String)

/**
 * Compiles bounded reachability to one node SELECT and one edge SELECT.
 * TRAVERSED returns all qualifying witness edges; INDUCED returns all eligible
 * physical relationships between reached output nodes.
 * Reification constraints on an edge must be satisfied by the SAME statement.
 */
class NetworkLensCompiler(
    private val selector: SparqlCompiler = SparqlCompiler(),
    private val maxQueryChars: Int = 100_000
) {
    init { require(maxQueryChars >= 256) }
    private val ns = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    private fun iri(raw: String): String {
        require(raw.startsWith("urn:") || raw.startsWith("https://") || raw.startsWith("http://")) {
            "Absolute IRI required"
        }
        require(raw.none {
            it.isWhitespace() || it in "<>\"{}|^" || it.code == 96 || it == '\\'
        }) { "Unsafe IRI" }
        return "<" + raw + ">"
    }
    private fun term(v: Value): String = when(v) {
        is Value.Iri -> iri(v.value)
        is Value.TypedLiteral -> {
            val text = v.lexical.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            "\"" + text + "\"^^" + iri(v.datatype)
        }
        is Value.Literal -> {
            val text = v.lexical.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            "\"" + text + "\""
        }
    }
    private fun graph(n: NetworkLens): String = n.datasetGraph?.let { " FROM " + iri(it) } ?: ""
    /**
     * A node guard. A guard without a union yields at most one solution per node and
     * binds nothing else, so it is emitted inline where the provider can join and
     * order it with the walk. A union could repeat a walk row, so it stays in EXISTS.
     */
    private fun nodeFilter(lens: Lens?, node: String): String = when {
        lens == null -> ""
        singleSolution(lens) -> selector.patternOfOptimized(lens, node)
        else -> "FILTER EXISTS { " + selector.patternOfOptimized(lens, node) + " }"
    }

    /** Exists targets and Difference right sides compile inside EXISTS and never add rows. */
    private fun singleSolution(lens: Lens): Boolean = when (lens) {
        is Lens.Type -> true
        is Lens.Equals -> singleSolution(lens.input)
        is Lens.Exists -> singleSolution(lens.input)
        is Lens.Filter -> singleSolution(lens.input)
        is Lens.Difference -> singleSolution(lens.left)
        is Lens.Intersect -> lens.parts.all { singleSolution(it) }
        is Lens.Union -> false
    }

    private fun metadata(
        s: String, p: String, o: String, constraints: List<EdgeCondition>, suffix: String
    ): String {
        if (constraints.isEmpty()) return ""
        val statement = "?statement" + suffix
        val parts = listOf(
            statement + " " + iri(ns + "subject") + " " + s + " .",
            statement + " " + iri(ns + "predicate") + " " + p + " .",
            statement + " " + iri(ns + "object") + " " + o + " ."
        ) + constraints.map { statement + " " + iri(it.predicate) + " " + term(it.value) + " ." }
        return parts.joinToString(" ")
    }

    /**
     * One guarded edge expansion; its annotation is correlated to actual edge orientation.
     * Emitted without its own group so it joins the walk's basic graph pattern and the
     * provider can order it from the seeds. [reverse] fixes the physical orientation;
     * null follows the lens direction, which needs a UNION only for BOTH.
     */
    private fun hop(
        n: NetworkLens, src: String, dst: String, tag: String, emitEdge: Boolean, reverse: Boolean?
    ): String {
        val predicate = iri(n.predicate)
        val conditions = n.traversalEdge + if (emitEdge) n.outputEdge else emptyList()
        fun case(subject: String, obj: String, direction: String): String =
            (subject + " " + predicate + " " + obj + " . " +
                metadata(subject, predicate, obj, conditions, tag + direction)).trimEnd()
        val orientation = reverse ?: when (n.direction) {
            NetworkDirection.OUT -> false
            NetworkDirection.IN -> true
            NetworkDirection.BOTH -> null
        }
        return when (orientation) {
            false -> case(src, dst, "f")
            true -> case(dst, src, "r")
            null -> "{ { " + case(src, dst, "f") + " } UNION { " + case(dst, src, "r") + " } }"
        }
    }

    /**
     * Each distinct branch is a valid walk, with guards on every intermediate node/edge.
     * Result variables are used directly as walk variables, so no BIND separates the
     * walk from its projection: without an emitted hop the last node is [endpoint] and
     * must pass the output guard; with one, that hop runs between ?subject and ?object
     * in the physical orientation given by [reverse].
     */
    private fun walk(
        n: NetworkLens, depth: Int, prefix: String,
        endpoint: String = "?node", emittedHop: Int = -1, reverse: Boolean = false
    ): String {
        val vars = (0..depth).map { "?" + prefix + "v" + it }.toMutableList()
        if (emittedHop < 0) {
            vars[depth] = endpoint
        } else {
            vars[emittedHop] = if (reverse) "?object" else "?subject"
            vars[emittedHop + 1] = if (reverse) "?subject" else "?object"
        }
        val pieces = arrayListOf(
            selector.patternOfOptimized(n.seeds, vars[0]), nodeFilter(n.traversalNode, vars[0])
        )
        for (i in 0 until depth) {
            val emitted = i == emittedHop
            pieces.add(hop(n, vars[i], vars[i+1], prefix + "h" + i, emitted, if (emitted) reverse else null))
            pieces.add(nodeFilter(n.traversalNode, vars[i+1]))
        }
        if (emittedHop < 0) pieces.add(nodeFilter(n.outputNode, endpoint))
        return "{ " + pieces.filter { it.isNotBlank() }.joinToString("\n") + " }"
    }

    private fun reachable(n: NetworkLens, prefix: String, endpoint: String = "?node"): String =
        "{ " + (0..n.maxDepth).joinToString(" UNION ") {
            walk(n, it, prefix + "d" + it + "_", endpoint)
        } + " }"

    /** The output node set, computed once and joined, never re-walked per candidate edge. */
    private fun reachableSet(n: NetworkLens, prefix: String, variable: String): String =
        "{ SELECT DISTINCT " + variable + " WHERE {\n" + reachable(n, prefix, variable) + "\n} }"

    private fun nodes(n: NetworkLens): String =
        "SELECT DISTINCT ?node" + graph(n) + " WHERE {\n" +
            reachable(n, "nodes_") + "\n} ORDER BY ?node"

    /**
     * One branch per walk length and physical orientation, emitting its final hop.
     * A hop followed by further hops is already witnessed by the shorter walk that
     * ends at that hop (same seeds, guards and edge conditions), so longer
     * continuations add no edges.
     */
    private fun traversed(n: NetworkLens): String {
        if (n.maxDepth == 0) return emptyEdges(n)
        val orientations = when (n.direction) {
            NetworkDirection.OUT -> listOf(false)
            NetworkDirection.IN -> listOf(true)
            NetworkDirection.BOTH -> listOf(false, true)
        }
        val branches = arrayListOf<String>()
        for (depth in 1..n.maxDepth) for (reverse in orientations) {
            val tag = "trace" + depth + (if (reverse) "r" else "f") + "_"
            branches.add(walk(n, depth, tag, emittedHop = depth - 1, reverse = reverse))
        }
        val guards = listOf(nodeFilter(n.outputNode, "?subject"), nodeFilter(n.outputNode, "?object"))
            .filter { it.isNotBlank() }.joinToString("") { it + "\n" }
        return "SELECT DISTINCT ?subject ?predicate ?object" + graph(n) + " WHERE {\n" +
            "{ " + branches.joinToString(" UNION\n") + " }\n" + guards +
            "BIND(" + iri(n.predicate) + " AS ?predicate)\n} ORDER BY ?subject ?predicate ?object"
    }

    /**
     * The subject set is joined to the edge pattern before the object set so that
     * left-to-right evaluation never forms the subject x object cross product.
     */
    private fun induced(n: NetworkLens): String {
        val predicate = iri(n.predicate)
        return "SELECT DISTINCT ?subject ?predicate ?object" + graph(n) + " WHERE {\n" +
            reachableSet(n, "subinduced_", "?subject") + "\n" +
            "?subject " + predicate + " ?object .\n" +
            metadata("?subject",predicate,"?object",n.traversalEdge+n.outputEdge,"induced") + "\n" +
            reachableSet(n, "objinduced_", "?object") + "\n" +
            "BIND(" + predicate + " AS ?predicate)\n" +
            "} ORDER BY ?subject ?predicate ?object"
    }

    private fun emptyEdges(n: NetworkLens): String =
        "SELECT ?subject ?predicate ?object" + graph(n) + " WHERE { FILTER(false) }"

    fun compile(network: NetworkLens): CompiledNetwork {
        // Optimize each lens once; the walks below serialize it many times.
        val n = network.copy(
            seeds = selector.optimized(network.seeds),
            traversalNode = network.traversalNode?.let { selector.optimized(it) },
            outputNode = network.outputNode?.let { selector.optimized(it) }
        )
        val nodes = nodes(n)
        val edges = if (n.closure == NetworkClosure.TRAVERSED) traversed(n) else induced(n)
        require(nodes.length <= maxQueryChars && edges.length <= maxQueryChars) {
            "Network query exceeded limit; reduce depth or guard complexity"
        }
        return CompiledNetwork(nodes,edges)
    }
}
