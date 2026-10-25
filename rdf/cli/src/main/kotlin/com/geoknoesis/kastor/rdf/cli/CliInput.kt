package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.jena.blankNodeIdOfSkolemGraph
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import java.io.CharConversionException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UTFDataFormatException
import java.io.UncheckedIOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Parsed input: the default graph plus named graphs (empty for graph formats, and for datasets without named graphs).
 *
 * A graph is named by an [Iri] or, when the input names it by a blank node (TriG `_:g { }`, an N-Quads graph label),
 * by that [BlankNode]: the provider loads such a graph under a skolem IRI (`urn:kastor:skolem:<load>:<id>`, see
 * [blankNodeIdOfSkolemGraph]) that differs on every parse, so [read] turns it back into the blank node it stands for.
 * That node is the one the triples of the same input use, so a blank node that names a graph and occurs in triples
 * stays one node. (An input that itself uses an IRI of that skolem form as a graph name is read as a blank node too.)
 */
internal class ParsedInput(val defaultGraph: RdfGraph, val namedGraphs: Map<RdfResource, RdfGraph>) {
    val tripleCount: Int get() = defaultGraph.size() + namedGraphs.values.sumOf { it.size() }

    /** The graphs named by IRIs, by IRI. */
    val iriGraphs: Map<String, RdfGraph> =
        namedGraphs.entries.mapNotNull { (name, graph) -> (name as? Iri)?.let { it.value to graph } }.toMap()

    /** How many graphs are named by blank nodes: they have no name that two inputs could share. */
    val blankGraphCount: Int = namedGraphs.keys.count { it is BlankNode }

    /**
     * The dataset as one graph: every quad becomes `<graph> <urn:kastor:rdf-cli:contains> <<( s p o )>>`, so one graph
     * isomorphism check maps blank nodes consistently across the default graph and all named graphs, the blank nodes
     * that name graphs included (they are the subjects of their quads). Default-graph quads use a distinct predicate
     * so no input graph IRI can alias their encoding.
     *
     * It is a view: the quads are wrapped as they are read and the graphs are not copied into a second indexed graph.
     */
    fun asQuadGraph(): RdfGraph = QuadView(listOf(null to defaultGraph) + namedGraphs.map { it.key to it.value })

    private class QuadView(private val graphs: List<Pair<RdfResource?, RdfGraph>>) : RdfGraph {
        private val byName = graphs.toMap()

        override fun getTriplesSequence(): Sequence<RdfTriple> =
            graphs.asSequence().flatMap { (name, graph) ->
                graph.getTriplesSequence().map { RdfTriple(name ?: DEFAULT_GRAPH, if (name == null) DEFAULT_CONTAINS else CONTAINS, TripleTerm(it)) }
            }

        override fun getTriples(): List<RdfTriple> = getTriplesSequence().toList()

        override fun hasTriple(triple: RdfTriple): Boolean {
            val quad = triple.obj as? TripleTerm ?: return false
            return when (triple.predicate) {
                DEFAULT_CONTAINS -> triple.subject == DEFAULT_GRAPH && byName[null]?.hasTriple(quad.triple) == true
                CONTAINS -> byName[triple.subject]?.hasTriple(quad.triple) == true
                else -> false
            }
        }

        override fun size(): Int = graphs.sumOf { it.second.size() }
    }

    private companion object {
        val DEFAULT_GRAPH = Iri("urn:kastor:rdf-cli:default-graph")
        val CONTAINS = Iri("urn:kastor:rdf-cli:contains")
        val DEFAULT_CONTAINS = Iri("urn:kastor:rdf-cli:default-contains")
    }
}

/**
 * Parses [path] as [format], resolving relative IRIs against [baseIri] (without one they are a parse error).
 *
 * A syntax error is a [CliError] with [EXIT_USAGE] that names the file; a read that failed (also half-way, which a
 * parser reports like a syntax error) is a [CliError] with [EXIT_RUNTIME_ERROR].
 */
internal fun read(path: Path, format: String, runtime: CliRuntime, baseIri: String? = null): ParsedInput {
    var stream: IoTrackingInputStream? = null
    try {
        val provider = JenaProvider()
        val input = IoTrackingInputStream(runtime.open(path)).also { stream = it }
        // Every format is read as a dataset, not only the quad formats (TriG, N-Quads): JSON-LD can name graphs too,
        // and reading it as a graph would drop them (Jena only logs a warning), so that `diff` would call two datasets
        // that differ in a named graph isomorphic. A graph format simply yields no named graphs.
        return JenaRepository.MemoryRepository().use { repo ->
            input.use { provider.parseDataset(repo, it, format, baseIri) }
            ParsedInput(
                MemoryGraph(repo.defaultGraph.getTriples()),
                repo.listGraphs().associate { name: Iri -> graphName(name) to MemoryGraph(repo.getGraph(name).getTriples()) },
            )
        }
    } catch (e: Exception) {
        // A parser reports a read that failed half-way like a syntax error; the file could not be read, which is a
        // runtime failure (status 3), not an error in its content (status 1).
        val failure = stream?.failure ?: ioFailureIn(e)
        if (failure == null) {
            if (e is RdfFormatException) throw CliError("Parse error in $path: ${oneLine(e.message)}")
            throw e
        }
        throw CliError(
            "kastor-rdf: error: I/O error reading $path: ${failure.javaClass.simpleName}: ${oneLine(failure.message ?: "(no message)")}",
            EXIT_RUNTIME_ERROR,
        )
    }
}

/** The name of a loaded graph as the input wrote it: the blank node a skolem graph name stands for, or the IRI. */
private fun graphName(name: Iri): RdfResource = blankNodeIdOfSkolemGraph(name)?.let(::BlankNode) ?: name

/** Remembers an [IOException] raised while reading, so a read failure is not reported as a syntax error. */
private class IoTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    var failure: IOException? = null
        private set

    override fun read(): Int =
        try {
            super.read()
        } catch (e: IOException) {
            failure = e
            throw e
        }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        try {
            super.read(b, off, len)
        } catch (e: IOException) {
            failure = e
            throw e
        }
}

/**
 * The I/O failure in the cause chain of [e], if any: a JDK [IOException] (or what wraps one: [UncheckedIOException],
 * Jena's `RuntimeIOException`). Exceptions that derive from [IOException] but report bad content are not I/O
 * failures: character decoding errors, and the parse exceptions of libraries (JSON parsers) that extend it.
 */
internal fun ioFailureIn(e: Throwable): Throwable? {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth++ < 32) {
        val next = current.cause?.takeIf { it !== current }
        when (current) {
            is CharacterCodingException, is CharConversionException, is UTFDataFormatException -> return null
            is UncheckedIOException -> if (next == null) return current
            is IOException -> return if (current.javaClass.name.startsWith("java.")) current else null
            else -> if (current.javaClass.name == JENA_RUNTIME_IO_EXCEPTION && next == null) return current
        }
        current = next
    }
    return null
}

private const val JENA_RUNTIME_IO_EXCEPTION = "org.apache.jena.atlas.RuntimeIOException"

internal fun requireRegular(path: Path): Path {
    if (!path.isRegularFile()) throw CliError("Not a regular file: $path")
    return path
}

internal fun formatOrInfer(path: Path, explicit: String?): String {
    if (explicit != null) {
        return RdfFormat.fromString(explicit)?.formatName ?: throw CliError("Unknown format: $explicit")
    }
    return when (path.extension.lowercase()) {
        "ttl" -> "TURTLE"
        "nt" -> "NTRIPLES"
        "nq" -> "NQUADS"
        "trig" -> "TRIG"
        "jsonld", "json" -> "JSON-LD"
        "rdf", "owl", "xml" -> "RDFXML"
        else -> throw CliError("Cannot infer the RDF format of '${path.name}'; pass FORMAT explicitly")
    }
}
