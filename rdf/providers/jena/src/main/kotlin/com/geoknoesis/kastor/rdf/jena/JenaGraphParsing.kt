package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.riot.RiotException

/** The graph-only side of Jena parsing: what a graph parse does with a statement in a named graph, or a parse error. */
internal object JenaGraphParsing {
    /**
     * [StreamRDF][org.apache.jena.riot.system.StreamRDF] of a graph parse into [graph]: validated like
     * [JenaParsing.validating], and a statement in a named graph (JSON-LD `@graph` under an `@id`) fails the parse
     * ([refuseNamedGraph]) instead of being dropped, as Jena's graph sink does with only a logged warning.
     */
    fun validatingGraph(format: String, graph: org.apache.jena.graph.Graph): org.apache.jena.riot.system.StreamRDF =
        JenaParsing.validating(object : org.apache.jena.riot.system.StreamRDFWrapper(org.apache.jena.riot.system.StreamRDFLib.graph(graph)) {
            override fun quad(quad: org.apache.jena.sparql.core.Quad) {
                if (!quad.isDefaultGraph) throw refuseNamedGraph(format, quad.graph)
                triple(quad.asTriple())
            }
        })

    /**
     * The triple of an element of an asynchronous graph parse, or null for a prefix or base declaration; a
     * statement in a named graph fails the parse ([refuseNamedGraph]), a parse error is rethrown.
     */
    fun graphTripleOf(format: String, element: org.apache.jena.riot.system.EltStreamRDF): org.apache.jena.graph.Triple? = when {
        element.isTriple -> element.triple()
        element.isQuad && element.quad().isDefaultGraph -> element.quad().asTriple()
        element.isQuad -> throw refuseNamedGraph(format, element.quad().graph)
        element.isException -> throw element.exception().let { e ->
            // The parser thread's stack overflowed on deep nesting (Jena wraps the error): a parse error.
            if (e is StackOverflowError || e.cause is StackOverflowError) RiotException("the document is nested too deeply for the parser", e)
            else JenaLiteralBounds.formatErrorOf(e) ?: e as? RuntimeException ?: RiotException(e)
        }
        else -> null
    }

    /** The parse error for a statement in the named graph [graph] met while parsing a single graph. */
    fun refuseNamedGraph(format: String, graph: org.apache.jena.graph.Node): RiotException = RiotException(
        "the $format document holds statements in the named graph " +
            "${if (graph.isURI) "<${graph.uri}>" else graph.toString()}; parsing it as a single graph would drop " +
            "them. Use parseDataset(...) to keep the named graphs.",
    )
}
