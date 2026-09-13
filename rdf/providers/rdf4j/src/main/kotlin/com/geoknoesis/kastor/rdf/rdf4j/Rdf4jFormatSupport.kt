package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.eclipse.rdf4j.rio.RDFParseException
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.UnsupportedRDFormatException
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler
import org.eclipse.rdf4j.rio.helpers.RDFHandlerWrapper
import java.io.InputStream
import java.io.StringWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RDF4J format support utilities for serialization and parsing.
 *
 * This is an internal implementation detail and should not be used directly.
 * Use the provider-agnostic API via [RdfProvider.serializeGraph] and [RdfProvider.parseGraph].
 *
 * **Parsing contract** (shared with the Jena provider):
 * - Streams are handed to Rio directly; statements are converted as they are parsed.
 * - Without a base IRI, relative IRI references are a parse error.
 * - [parseGraph] rejects quad formats (TriG, N-Quads) instead of merging their named graphs.
 * - Only genuine syntax/format failures (Rio parse errors, unsupported formats, I/O errors) become
 *   [RdfFormatException]; programming errors (NPE, ClassCastException, ...) propagate unchanged.
 */
internal object Rdf4jFormatSupport {

    /** Formats RDF4J 5.3 really implements (RDF 1.1 syntaxes; no RDF 1.2 `-1.2` aliases). */
    val ADVERTISED_FORMATS = listOf(
        "TURTLE", "TTL",
        "JSON-LD", "JSONLD",
        "RDF/XML", "RDFXML", "XML",
        "N-TRIPLES", "NT", "NTRIPLES",
        "TRIG", "TRI-G",
        "N-QUADS", "NQUADS", "NQ",
    )

    /**
     * Convert a Kastor format string to an RDF4J [RDFFormat]. The RDF 1.2 alias spellings
     * (`TURTLE-1.2`, ...) are still accepted when named explicitly, but they are not advertised
     * because Rio does not implement the RDF 1.2 syntax suite.
     */
    private fun toRdf4jFormat(format: String): RDFFormat {
        val normalized = format.uppercase().trim()
        return when (normalized) {
            "TURTLE", "TTL", "TURTLE-1.2", "TURTLE12", "TURTLESTAR" -> RDFFormat.TURTLE
            "JSON-LD", "JSONLD", "JSON-LD-1.2", "JSONLD12" -> RDFFormat.JSONLD
            "RDF/XML", "RDFXML", "XML" -> RDFFormat.RDFXML
            "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2", "NTRIPLES12" -> RDFFormat.NTRIPLES
            "TRIG", "TRI-G", "TRIG-1.2", "TRIG12", "TRIGSTAR" -> RDFFormat.TRIG
            "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2", "NQUADS12" -> RDFFormat.NQUADS
            else -> throw RdfFormatException.UnsupportedFormat(format, ADVERTISED_FORMATS)
        }
    }

    private fun graphFormat(format: String): RDFFormat {
        val rdf4jFormat = toRdf4jFormat(format)
        if (rdf4jFormat == RDFFormat.TRIG || rdf4jFormat == RDFFormat.NQUADS) {
            throw RdfFormatException.Generic(
                "Format '$format' is a quad (dataset) format; parsing it as a single graph would drop or merge " +
                    "named graphs. Use parseDataset(...) instead.",
                RdfErrorCode.FORMAT_PARSE_ERROR,
            )
        }
        return rdf4jFormat
    }

    /** Maps only format-level failures to [RdfFormatException]; everything else propagates unchanged. */
    private inline fun <T> formatErrors(what: String, block: () -> T): T = try {
        block()
    } catch (e: RDFParseException) {
        throw RdfFormatException.Generic("Failed to parse $what: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
    } catch (e: UnsupportedRDFormatException) {
        throw RdfFormatException.Generic("Unsupported $what: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
    } catch (e: java.io.IOException) {
        throw RdfFormatException.Generic("Failed to read $what: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
    }

    private fun writerFor(format: RDFFormat, writer: StringWriter, options: SerializationOptions): RDFHandler {
        // Base declarations are only emitted for formats that have one (Turtle / TriG), matching Jena.
        val base = options.baseUri?.takeIf { format == RDFFormat.TURTLE || format == RDFFormat.TRIG }
        val rioWriter = if (base != null) Rio.createWriter(format, writer, base) else Rio.createWriter(format, writer)
        return object : RDFHandlerWrapper(rioWriter) {
            override fun startRDF() {
                super.startRDF()
                options.prefixMappings.forEach { (prefix, uri) -> super.handleNamespace(prefix, uri) }
            }
        }
    }

    /**
     * Serialize a Kastor RdfGraph to a string using RDF4J, honouring prefixes and base from [options].
     */
    fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val rdf4jFormat = toRdf4jFormat(format)
        val model = LinkedHashModel()
        graph.getTriples().forEach { triple ->
            model.add(
                Rdf4jTerms.toRdf4jResource(triple.subject),
                Rdf4jTerms.toRdf4jIri(triple.predicate),
                Rdf4jTerms.toRdf4jValue(triple.obj),
            )
        }
        val writer = StringWriter()
        Rio.write(model as Iterable<Statement>, writerFor(rdf4jFormat, writer, options))
        return writer.toString()
    }

    /**
     * Parse RDF data from an input stream into a Kastor graph using RDF4J (no base IRI).
     */
    fun parseGraph(inputStream: InputStream, format: String): MutableRdfGraph = parseGraph(inputStream, format, "")

    /**
     * Parse RDF data into a graph using an explicit base IRI.
     *
     * @param baseIri base used to resolve relative IRIs in the source; empty means "no base"
     *   (relative IRIs are then a parse error).
     */
    fun parseGraph(inputStream: InputStream, format: String, baseIri: String): MutableRdfGraph {
        val rdf4jFormat = graphFormat(format)
        val triples = mutableListOf<RdfTriple>()
        formatErrors("$format data") {
            val parser = Rio.createParser(rdf4jFormat)
            parser.setRDFHandler(object : AbstractRDFHandler() {
                override fun handleStatement(statement: Statement) {
                    triples.add(toTriple(statement))
                }
            })
            parser.parse(inputStream, baseIri)
        }
        return com.geoknoesis.kastor.rdf.provider.MemoryGraph(triples)
    }

    private fun toTriple(statement: Statement) = RdfTriple(
        Rdf4jTerms.fromRdf4jResource(statement.subject),
        Rdf4jTerms.fromRdf4jIri(statement.predicate),
        Rdf4jTerms.fromRdf4jValue(statement.`object`),
    )

    /**
     * Opens a streaming parse: Rio runs on a daemon thread and hands triples over through a bounded
     * queue, so memory stays constant regardless of document size. Closing the stream stops the
     * parser and closes [inputStream].
     */
    fun openTripleStream(inputStream: InputStream, format: String): TripleStream {
        val rdf4jFormat = graphFormat(format)
        return Rdf4jTripleStream(inputStream, rdf4jFormat, format)
    }

    private class Rdf4jTripleStream(
        private val input: InputStream,
        rdf4jFormat: RDFFormat,
        private val formatName: String,
    ) : TripleStream {
        private object End
        private class Failure(val error: Throwable)
        private class Cancelled : RuntimeException(null, null, false, false)

        private val queue = ArrayBlockingQueue<Any>(1024)
        @Volatile private var closed = false
        private var next: Any? = null
        private var finished = false
        private var iterated = false

        private val producer = Thread({
            try {
                val parser = Rio.createParser(rdf4jFormat)
                parser.setRDFHandler(object : AbstractRDFHandler() {
                    override fun handleStatement(statement: Statement) = offer(toTriple(statement))
                })
                parser.parse(input, "")
                offer(End)
            } catch (_: Cancelled) {
                // closed by the consumer
            } catch (e: Throwable) {
                if (!closed) runCatching { offer(Failure(e)) }
            }
        }, "kastor-rdf4j-stream-parser").apply { isDaemon = true }

        init { producer.start() }

        private fun offer(item: Any) {
            while (!queue.offer(item, 50, TimeUnit.MILLISECONDS)) {
                if (closed) throw Cancelled()
            }
            if (closed) throw Cancelled()
        }

        private fun advance(): Boolean {
            check(!closed) { "Triple stream is closed" }
            if (finished) return false
            if (next == null) next = queue.take()
            return when (val item = next) {
                End -> { finished = true; false }
                is Failure -> {
                    finished = true
                    val error = item.error
                    formatErrors("$formatName data") { throw error }
                }
                else -> true
            }
        }

        override fun iterator(): Iterator<RdfTriple> {
            check(!closed) { "Triple stream is closed" }
            check(!iterated) { "Triple stream can only be iterated once" }
            iterated = true
            return object : Iterator<RdfTriple> {
                override fun hasNext(): Boolean = advance()
                override fun next(): RdfTriple {
                    if (!advance()) throw NoSuchElementException()
                    return (next as RdfTriple).also { next = null }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try { input.close() } finally { queue.clear() }
        }
    }

    /**
     * Serialize a Kastor RdfRepository (dataset) to a string using RDF4J, honouring prefixes and base.
     */
    fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val rdf4jFormat = toRdf4jFormat(format)
        val rdf4jRepo = repository as? Rdf4jRepository
            ?: throw UnsupportedOperationException("Rdf4jFormatSupport can only serialize RDF4J repositories")

        return rdf4jRepo.withConnection { connection ->
            val writer = StringWriter()
            try {
                // Stream statements straight from the store to the Rio writer. A context-less
                // export() covers the default graph and all named contexts, each with its context.
                connection.export(writerFor(rdf4jFormat, writer, options))
                writer.toString()
            } catch (e: org.eclipse.rdf4j.rio.RDFHandlerException) {
                throw RdfFormatException.Generic("Failed to serialize dataset: ${e.message}", RdfErrorCode.FORMAT_SERIALIZATION_ERROR, e)
            }
        }
    }

    /**
     * Parse RDF dataset data from an input stream into a Kastor repository using RDF4J.
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String) {
        parseDataset(repository, inputStream, format, "")
    }

    /**
     * Parse a dataset using an explicit base IRI.
     */
    fun parseDataset(
        repository: RdfRepository,
        inputStream: InputStream,
        format: String,
        baseIri: String,
    ) {
        val rdf4jFormat = toRdf4jFormat(format)
        val rdf4jRepo = repository as? Rdf4jRepository
            ?: throw UnsupportedOperationException("Rdf4jFormatSupport can only parse into RDF4J repositories")

        rdf4jRepo.withWriteConnection { connection ->
            // withWriteConnection runs inside a transaction (joining an outer transaction { } on this
            // thread), so a parse failure mid-stream rolls back instead of leaving partial data.
            formatErrors("$format dataset") {
                val parser = Rio.createParser(rdf4jFormat)
                parser.setRDFHandler(object : AbstractRDFHandler() {
                    override fun handleStatement(statement: Statement) {
                        val context = statement.context
                        if (context != null) {
                            connection.add(statement.subject, statement.predicate, statement.`object`, context)
                        } else {
                            connection.add(statement.subject, statement.predicate, statement.`object`)
                        }
                    }
                })
                parser.parse(inputStream, baseIri)
            }
        }
    }
}
