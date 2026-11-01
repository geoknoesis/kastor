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
    private fun nodeFilter(lens: Lens?, node: String): String =
        if (lens == null) "" else "FILTER EXISTS { " + selector.graphPattern(lens, node) + " }"

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

    /** One guarded edge expansion; its annotation is correlated to actual edge orientation. */
    private fun hop(n: NetworkLens, src: String, dst: String, tag: String, emitEdge: Boolean): String {
        val predicate = iri(n.predicate)
        val conditions = n.traversalEdge + if (emitEdge) n.outputEdge else emptyList()
        fun case(subject: String, obj: String, direction: String): String {
            val meta = metadata(subject, predicate, obj, conditions, tag + direction)
            val bind = if (emitEdge)
                " BIND(" + subject + " AS ?subject) BIND(" + obj + " AS ?object)"
                else ""
            return "{ " + subject + " " + predicate + " " + obj + " . " + meta + bind + " }"
        }
        return when(n.direction) {
            NetworkDirection.OUT -> case(src,dst,"f")
            NetworkDirection.IN -> case(dst,src,"r")
            NetworkDirection.BOTH -> "{ " + case(src,dst,"f") + " UNION " + case(dst,src,"r") + " }"
        }
    }

    /** Each distinct branch is a valid walk, with guards on every intermediate node/edge. */
    private fun walk(
        n: NetworkLens, depth: Int, prefix: String,
        reached: String? = null, emittedHop: Int = -1
    ): String {
        val vars = (0..depth).map { "?" + prefix + "v" + it }
        val pieces = arrayListOf(selector.graphPattern(n.seeds, vars[0]), nodeFilter(n.traversalNode, vars[0]))
        for (i in 0 until depth) {
            pieces.add(hop(n, vars[i], vars[i+1], prefix + "h" + i, i == emittedHop))
            pieces.add(nodeFilter(n.traversalNode, vars[i+1]))
        }
        if (emittedHop < 0) {
            if (reached == null) {
                pieces.add(nodeFilter(n.outputNode, vars.last()))
                pieces.add("BIND(" + vars.last() + " AS ?node)")
            } else {
                pieces.add("FILTER(" + vars.last() + " = " + reached + ")")
            }
        }
        return "{ " + pieces.filter { it.isNotBlank() }.joinToString("\n") + " }"
    }

    private fun reachable(n: NetworkLens, endpoint: String?, prefix: String): String =
        "{ " + (0..n.maxDepth).joinToString(" UNION ") {
            walk(n, it, prefix + "d" + it + "_", endpoint)
        } + " }"
    private fun visible(n: NetworkLens, endpoint: String, tag: String): String =
        "FILTER EXISTS { " + reachable(n, endpoint, tag) + " " +
            nodeFilter(n.outputNode, endpoint) + " }"

    private fun nodes(n: NetworkLens): String =
        "SELECT DISTINCT ?node" + graph(n) + " WHERE {\n" +
            reachable(n, null, "nodes_") + "\n} ORDER BY ?node"

    private fun traversed(n: NetworkLens): String {
        if (n.maxDepth == 0) return emptyEdges(n)
        val branches = arrayListOf<String>()
        for (depth in 1..n.maxDepth) for (selectedHop in 0 until depth) {
            val tag = "trace" + depth + "_" + selectedHop + "_"
            branches.add("{ " + walk(n,depth,tag,emittedHop=selectedHop) + "\n" +
                nodeFilter(n.outputNode, "?subject") + "\n" +
                nodeFilter(n.outputNode, "?object") + "\n" +
                "BIND(" + iri(n.predicate) + " AS ?predicate) }")
        }
        return "SELECT DISTINCT ?subject ?predicate ?object" + graph(n) + " WHERE {\n" +
            branches.joinToString(" UNION\n") + "\n} ORDER BY ?subject ?predicate ?object"
    }

    private fun induced(n: NetworkLens): String {
        val predicate = iri(n.predicate)
        return "SELECT DISTINCT ?subject ?predicate ?object" + graph(n) + " WHERE {\n" +
            "?subject " + predicate + " ?object .\n" +
            metadata("?subject",predicate,"?object",n.traversalEdge+n.outputEdge,"induced") + "\n" +
            visible(n,"?subject","subinduced_") + "\n" +
            visible(n,"?object","objinduced_") + "\n" +
            "BIND(" + predicate + " AS ?predicate)\n" +
            "} ORDER BY ?subject ?predicate ?object"
    }

    private fun emptyEdges(n: NetworkLens): String =
        "SELECT ?subject ?predicate ?object" + graph(n) + " WHERE { FILTER(false) }"

    fun compile(n: NetworkLens): CompiledNetwork {
        val nodes = nodes(n)
        val edges = if (n.closure == NetworkClosure.TRAVERSED) traversed(n) else induced(n)
        require(nodes.length <= maxQueryChars && edges.length <= maxQueryChars) {
            "Network query exceeded limit; reduce depth or guard complexity"
        }
        return CompiledNetwork(nodes,edges)
    }
}
