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
 * - [parseGraph] rejects quad formats (TriG, N-Quads) instead of merging their named graphs, and the graph parses
 *   ([parseGraph], [openTripleStream]) fail with [RdfFormatException] on a document of a graph format that names a
 *   graph (JSON-LD `@graph` under an `@id`) instead of merging it into the one graph.
 * - Every failure of Rio itself becomes [RdfFormatException] (with the original as cause): parse errors, unsupported
 *   formats, I/O errors, and also unchecked exceptions Rio throws on input it does not handle (Rio 5.3 throws a
 *   NullPointerException on valid RDF 1.2 Turtle / TriG annotation syntax). Failures of Kastor's own code that
 *   receives the statements (a repository write, for example) propagate unchanged.
 * - Literals whose lexical form holds an unpaired UTF-16 surrogate (which Rio produces from numeric escapes of
 *   surrogate code points) are rejected with [RdfFormatException], as the Jena provider and the SPARQL JSON reader
 *   reject them; so are numeric literals longer than [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH], as on Jena.
 * - JSON-LD contexts must be inline: a context (or `@import`) named by URL is never loaded, whatever its scheme -
 *   also not one on RDF4J's own whitelist of well-known contexts - and fails the parse with [RdfFormatException]
 *   (the same rule as the Jena provider's).
 * - **Blank node labels are scoped to the document, everywhere in it**: a label names one node of the parsed data
 *   whether it is written as a subject, an object or a graph name, or inside a triple term, and never a node of
 *   another parse (see [DocumentBlankNodes]).
 */
internal object Rdf4jFormatSupport {

    /**
     * Prefix of the IRIs that replace blank-node graph names when a dataset is loaded into a repository (the same
     * scheme as the Jena provider's).
     */
    const val SKOLEM_GRAPH_PREFIX = "urn:kastor:skolem:"

    /** `urn:kastor:skolem:<load>:<label>`, with every byte of [label] outside `[A-Za-z0-9._-]` percent-encoded. */
    fun skolemGraphName(load: String, label: String): String {
        val name = StringBuilder(SKOLEM_GRAPH_PREFIX.length + load.length + 1 + label.length)
            .append(SKOLEM_GRAPH_PREFIX).append(load).append(':')
        for (byte in label.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') {
                name.append(c)
            } else {
                name.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return name.toString()
    }

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

    /**
     * A Rio parser for one document that feeds [handle] its statements with document-scoped blank nodes (see
     * [DocumentBlankNodes]). Rio keeps the labels as written (`PRESERVE_BNODE_IDS`), and they are renamed here, so
     * that labels inside triple terms get the same treatment as the others.
     */
    private fun parser(format: RDFFormat, handle: (Statement) -> Unit): org.eclipse.rdf4j.rio.RDFParser {
        val parser = Rio.createParser(format)
        parser.parserConfig.set(org.eclipse.rdf4j.rio.helpers.BasicParserSettings.PRESERVE_BNODE_IDS, true)
        // JSON-LD: never load a context named by URL (RDF4J's default loads the ones on its whitelist, unbounded).
        parser.parserConfig.set(org.eclipse.rdf4j.rio.jsonld.JSONLDSettings.DOCUMENT_LOADER, REFUSING_DOCUMENT_LOADER)
        val nodes = DocumentBlankNodes()
        parser.setRDFHandler(object : AbstractRDFHandler() {
            override fun handleStatement(statement: Statement) {
                try {
                    handle(nodes.statement(statement))
                } catch (e: RDFParseException) {
                    throw e // a term Kastor rejects (checkedTriples): an error of the input
                } catch (e: RuntimeException) {
                    throw HandlerFailure(e)
                }
            }
        })
        return parser
    }

    /** Refuses every document the JSON-LD parser would load (see the class documentation). */
    private val REFUSING_DOCUMENT_LOADER = no.hasmac.jsonld.loader.DocumentLoader { url, _ ->
        throw no.hasmac.jsonld.JsonLdError(
            no.hasmac.jsonld.JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
            "JSON-LD context <$url> was not loaded: Kastor never loads remote or local JSON-LD documents; " +
                "put the context inline",
        )
    }

    /**
     * A failure of Kastor's own statement handler (not of Rio), carried through the parser so that [formatErrors]
     * can tell it from Rio's own failures and rethrow it unchanged.
     */
    private class HandlerFailure(cause: RuntimeException) : RuntimeException(cause)

    /** The failure of the statement handler that [error] carries, or null when [error] is a failure of Rio. */
    private fun handlerFailure(error: Throwable): RuntimeException? =
        generateSequence(error) { it.cause }.take(16).filterIsInstance<HandlerFailure>().firstOrNull()?.cause as RuntimeException?

    /**
     * The message of [error], followed by the message of its root cause when that says more (Rio's JSON-LD parser
     * reports every failure as "Could not parse JSONLD" and keeps the reason in the cause).
     */
    private fun withRootCause(error: Throwable): String {
        val message = error.message.orEmpty()
        val root = generateSequence(error) { it.cause }.take(16).last().message
        return if (root.isNullOrBlank() || root in message) message else "$message ($root)"
    }

    /**
     * Maps the failures of Rio to [RdfFormatException], including unchecked exceptions Rio throws on input it does
     * not handle; a failure of the statement handler ([HandlerFailure]) and a Kastor [RdfException] propagate unchanged.
     */
    private inline fun <T> formatErrors(what: String, block: () -> T): T = try {
        block()
    } catch (e: RdfException) { throw e } catch (e: RuntimeException) {
        handlerFailure(e)?.let { throw it }
        throw when (e) {
            is RDFParseException -> RdfFormatException.Generic("Failed to parse $what: ${withRootCause(e)}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
            is UnsupportedRDFormatException -> RdfFormatException.Generic("Unsupported $what: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
            else -> RdfFormatException.Generic(
                "Failed to parse $what: the RDF4J parser failed unexpectedly (${e.javaClass.name}: ${e.message})",
                RdfErrorCode.FORMAT_PARSE_ERROR, e,
            )
        }
    } catch (e: java.io.IOException) {
        throw RdfFormatException.Generic("Failed to read $what: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
    }

    /**
     * The formats whose triple terms are written and read in RDF 1.2 syntax ([Rdf4jTripleTermSyntax]). Not N-Triples
     * and N-Quads (Rio 5.x reads RDF-star in neither), nor RDF/XML and JSON-LD (Rio 5.x has no triple-term syntax for
     * them): their triple terms stay `urn:rdf4j:triple:` IRIs, which only RDF4J reads back as triple terms.
     */
    private val TURTLE_FAMILY = setOf(RDFFormat.TURTLE, RDFFormat.TRIG)

    /** The text a [writerFor] writer wrote, with RDF 1.2 triple-term syntax in the Turtle family. */
    private fun rdf12Text(format: RDFFormat, text: String): String =
        if (format in TURTLE_FAMILY) Rdf4jTripleTermSyntax.toRdf12(text) else text

    /** [input] for Rio: in the Turtle family, RDF 1.2 triple terms become the RDF-star tokens Rio reads. */
    private fun rdf12Input(format: RDFFormat, input: InputStream): InputStream =
        if (format in TURTLE_FAMILY) Rdf4jTripleTermSyntax.fromRdf12(input) else input

    private fun writerFor(format: RDFFormat, writer: StringWriter, options: SerializationOptions): RDFHandler {
        // Base declarations are only emitted for formats that have one (Turtle / TriG), matching Jena.
        val base = options.baseUri?.takeIf { format == RDFFormat.TURTLE || format == RDFFormat.TRIG }
        // Turtle / TriG triple terms: RDF-star writers (`<< s p o >>`, turned into RDF 1.2 `<<( s p o )>>` by rdf12Text),
        // not the `urn:rdf4j:triple:` IRIs of Rio's RDF 1.1 writers, which the other formats keep.
        val writerFormat = when (format) { RDFFormat.TURTLE -> RDFFormat.TURTLESTAR; RDFFormat.TRIG -> RDFFormat.TRIGSTAR; else -> format }
        val rioWriter = if (base != null) Rio.createWriter(writerFormat, writer, base) else Rio.createWriter(writerFormat, writer)
        if (format in TURTLE_FAMILY) rioWriter.writerConfig.set(org.eclipse.rdf4j.rio.helpers.BasicWriterSettings.ENCODE_RDF_STAR, false)
        // Rio's Turtle / TriG writers write numbers and booleans bare by default, in their canonical form ("+1", "01"
        // and "1"^^xsd:boolean as 1, 1 and true): another literal. Written quoted, every lexical form is kept.
        rioWriter.writerConfig.set(org.eclipse.rdf4j.rio.turtle.TurtleWriterSettings.ABBREVIATE_NUMBERS, false)
        return object : RDFHandlerWrapper(rioWriter) {
            override fun startRDF() {
                super.startRDF()
                options.prefixMappings.forEach { (prefix, uri) -> super.handleNamespace(prefix, uri) }
            }

            // One label per blank node, inside and outside a triple term, whatever the writer (see WriterLabels).
            override fun handleStatement(statement: Statement) = super.handleStatement(WriterLabels.statement(statement))
        }
    }

    /**
     * Serialize a Kastor RdfGraph to a string using RDF4J, honouring prefixes and base from [options].
     *
     * Blank nodes are written under labels that are the same inside and outside a triple term (an id of ASCII letters
     * and digits as it is, any other id escaped, see [WriterLabels]), so that [parseGraph] reads a blank node that
     * occurs in both places back as one node.
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
        return rdf12Text(rdf4jFormat, writer.toString())
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
        val seen = HashSet<org.eclipse.rdf4j.model.Triple>()
        formatErrors("$format data") {
            parser(rdf4jFormat) { statement -> triples.addAll(checkedTriples(graphStatement(format, statement), seen)) }.parse(rdf12Input(rdf4jFormat, inputStream), baseIri)
        }
        return com.geoknoesis.kastor.rdf.provider.MemoryGraph(triples)
    }

    /**
     * [statement] of a graph parse; a statement in a named graph (JSON-LD `@graph` under an `@id`) is a parse error
     * instead of being merged into the one graph, as the Jena provider does.
     */
    private fun graphStatement(format: String, statement: Statement): Statement {
        val graph = statement.context ?: return statement
        throw RDFParseException(
            "the $format document holds statements in the named graph " +
                "${if (graph.isIRI) "<${graph.stringValue()}>" else graph.toString()}; parsing it as a single graph " +
                "would merge them into it. Use parseDataset(...) to keep the named graphs.",
        )
    }

    /**
     * Converts and validates a parsed statement; terms Kastor cannot represent become Rio parse errors.
     * Rio reads RDF 1.2 reified-triple syntax (`<< s p o >> :q :z`, annotations) as RDF-star quoted-triple
     * subjects; those map to the RDF 1.2 reified form (see [Rdf4jTerms.triplesOf]), with each `rdf:reifies`
     * triple emitted once per [seen] set.
     */
    private fun checkedTriples(statement: Statement, seen: MutableSet<org.eclipse.rdf4j.model.Triple>?): List<RdfTriple> = try {
        Rdf4jTerms.triplesOf(statement, seen).onEach { Rdf4jTerms.requireWellFormed(it.obj); requirePairedSurrogates(it) }
    } catch (e: IllegalArgumentException) {
        throw RDFParseException("Invalid RDF term: ${e.message}").also { it.initCause(e) }
    }

    /**
     * Rejects a literal (also inside a triple term) whose lexical form holds an unpaired UTF-16 surrogate: Rio decodes
     * a numeric escape of a surrogate code point into one, which is not a Unicode string (RDF 1.2 Turtle forbids such
     * escapes; the Jena provider and the SPARQL JSON reader reject them). IRIs are checked by [Iri] itself.
     * @throws IllegalArgumentException for such a literal
     */
    private fun requirePairedSurrogates(triple: RdfTriple) {
        when (val obj = triple.obj) {
            is Literal -> {
                require(hasPairedSurrogates(obj.lexical)) { "literal lexical form contains an unpaired surrogate" }
                // The limit the Jena provider needs (its parser computes literal values in quadratic time), for parity.
                LiteralValidation.numericLengthProblem(obj.datatype.value, obj.lexical)?.let { throw IllegalArgumentException(it) }
            }
            is TripleTerm -> requirePairedSurrogates(obj.triple)
            else -> Unit
        }
    }

    private fun hasPairedSurrogates(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate()) return false
                i++
            }
        }
        return true
    }

    /**
     * Opens a streaming parse: Rio runs on a daemon thread and hands triples over through a bounded
     * queue, so memory stays constant regardless of document size. Closing the stream stops the
     * parser and closes [inputStream].
     */
    fun openTripleStream(inputStream: InputStream, format: String, baseIri: String? = null): TripleStream =
        openTripleStream(inputStream, format, baseIri, hooks = StreamHooks.NONE)

    /**
     * What a test observes of the producer of a triple stream, instead of looking for its thread among the threads of
     * the JVM or polling for its progress.
     *
     * @property producerCreated told the producer thread of the stream, before it is started.
     * @property queueFull called on the producer thread each time it has a triple the full queue does not take: the
     *   producer reads no further input until the consumer takes a triple (or the stream is closed).
     */
    internal class StreamHooks(val producerCreated: (Thread) -> Unit = {}, val queueFull: () -> Unit = {}) {
        companion object {
            val NONE = StreamHooks()
        }
    }

    /**
     * [openTripleStream] with an injectable cleanup registrar, so tests can run the cleanup of an abandoned stream
     * deterministically instead of waiting for garbage collection, and with [hooks] to observe its producer.
     */
    internal fun openTripleStream(
        inputStream: InputStream,
        format: String,
        baseIri: String?,
        registerCleanup: (Any, Runnable) -> java.lang.ref.Cleaner.Cleanable = { stream, action -> Rdf4jTripleStream.CLEANER.register(stream, action) },
        hooks: StreamHooks = StreamHooks.NONE,
    ): TripleStream {
        val rdf4jFormat = graphFormat(format)
        return Rdf4jTripleStream(inputStream, rdf4jFormat, format, baseIri ?: "", registerCleanup, hooks)
    }

    /**
     * Background-parser triple stream.
     *
     * Lifecycle guarantees:
     * - [close] (from any thread) stops the parser, closes the input and wakes a consumer blocked waiting for
     *   the next triple; that consumer then fails with [IllegalStateException].
     * - The producer always delivers a terminal item (end, failure, or a pre-allocated failure marker when even
     *   the failure cannot be allocated), so a consumer never waits forever for a dead producer.
     * - A stream abandoned without [close] is closed by a [java.lang.ref.Cleaner] once it becomes unreachable,
     *   which stops its producer thread (the producer holds no reference to the stream itself).
     */
    private class Rdf4jTripleStream(
        input: InputStream,
        rdf4jFormat: RDFFormat,
        private val formatName: String,
        baseIri: String,
        registerCleanup: (Any, Runnable) -> java.lang.ref.Cleaner.Cleanable,
        hooks: StreamHooks,
    ) : TripleStream {
        private class Failure(val error: Throwable)
        private class Cancelled : RuntimeException(null, null, false, false)

        /** State shared with the producer thread; it must not reference the stream so an abandoned stream is collectable. */
        private class State(val input: InputStream) : Runnable {
            val queue = ArrayBlockingQueue<Any>(QUEUE_CAPACITY)
            @Volatile var closed = false

            /** Close action (also run by the cleaner): stop the producer and wake a waiting consumer. */
            override fun run() {
                if (closed) return
                closed = true
                try {
                    input.close()
                } catch (_: Exception) {
                    // closing is best effort
                } finally {
                    do { queue.clear() } while (!queue.offer(CLOSED))
                }
            }
        }

        private val state = State(input)
        private val cleanable = registerCleanup(this, state)
        private var next: Any? = null
        private var finished = false
        private var iterated = false

        init {
            val shared = state
            Thread({ produce(shared, rdf4jFormat, baseIri, hooks) }, PRODUCER_THREAD).apply { isDaemon = true }.also(hooks.producerCreated).start()
        }

        private fun advance(): Boolean {
            check(!state.closed) { "Triple stream is closed" }
            if (finished) return false
            if (next == null) next = state.queue.take()
            val item = next
            return when {
                item === CLOSED -> {
                    finished = true
                    throw IllegalStateException("Triple stream is closed")
                }
                item === END -> {
                    finished = true
                    false
                }
                item is Failure -> {
                    finished = true
                    val error = item.error
                    formatErrors("$formatName data") { throw error }
                }
                else -> true
            }
        }

        override fun iterator(): Iterator<RdfTriple> {
            check(!state.closed) { "Triple stream is closed" }
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

        override fun close() = cleanable.clean()

        companion object {
            const val PRODUCER_THREAD = "kastor-rdf4j-stream-parser"
            const val QUEUE_CAPACITY = 1024
            const val POLL_MILLIS = 50L
            val CLEANER: java.lang.ref.Cleaner = java.lang.ref.Cleaner.create()
            val END = Any()
            val CLOSED = Any()

            /** Delivered when the producer died and not even a [Failure] could be allocated (e.g. out of memory). */
            val PRODUCER_DIED = Failure(IllegalStateException("RDF4J stream parser thread terminated abnormally"))

            fun produce(state: State, format: RDFFormat, baseIri: String, hooks: StreamHooks) {
                var terminal: Any = PRODUCER_DIED
                try {
                    val seen = HashSet<org.eclipse.rdf4j.model.Triple>()
                    parser(format) { statement -> checkedTriples(graphStatement(format.name, statement), seen).forEach { offer(state, it, hooks) } }.parse(rdf12Input(format, state.input), baseIri)
                    terminal = END
                } catch (_: Cancelled) {
                    // closed: the close action already woke the consumer
                } catch (e: Throwable) {
                    // Cancelled is thrown by the statement handler, so it comes back wrapped (HandlerFailure).
                    if (generateSequence(e) { it.cause }.take(16).none { it is Cancelled }) {
                        terminal = try { Failure(e) } catch (_: Throwable) { PRODUCER_DIED }
                    }
                } finally {
                    while (!state.closed) {
                        if (try { state.queue.offer(terminal, POLL_MILLIS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }) break
                    }
                }
            }

            fun offer(state: State, item: Any, hooks: StreamHooks) {
                var full = false
                while (!state.queue.offer(item, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (state.closed) throw Cancelled()
                    if (!full) {
                        full = true
                        hooks.queueFull()
                    }
                }
                if (state.closed) throw Cancelled()
            }
        }
    }

    /**
     * Serialize a Kastor RdfRepository (dataset) to a string using RDF4J, honouring prefixes and base.
     *
     * Every graph is written as the graph API returns it (the view [serializeGraph] writes for one graph), so that
     * parsing the document gives graphs isomorphic to the original ones: a statement with an RDF-star subject is
     * written in its RDF 1.2 reified form (the reifier blank node `_:r` as subject, plus `_:r rdf:reifies <<( s p o )>>`),
     * and each `rdf:reifies` triple is written once per graph, whether it is stored (an explicit one), implied by a
     * statement about the quoted triple, or both. (Writing the stored statements as they are would write the quoted
     * triple as a subject **and** the stored `_:r rdf:reifies` statement; a parser gives that blank node a new label,
     * and the graph read back would have two reifiers for one triple.)
     *
     * Statements are streamed from the store, graph by graph (the default graph first); only the `rdf:reifies` triples
     * of the graph being written are remembered. Statements without a quoted-triple subject are written as stored.
     * Explicit statements only are written, also for an inference repository. Blank-node contexts of a wrapped store
     * are written with their blank graph label ([parseDataset] skolemizes it).
     *
     * A blank node that occurs inside a triple term **and** outside of it (for example the reifier of an annotation on
     * an annotated triple) stays one node when the document is parsed by [parseDataset] / [parseGraph]: a triple term
     * is written with the labels of the blank nodes inside it (in RDF 1.2 syntax in TriG, see [Rdf4jTripleTermSyntax];
     * as an `urn:rdf4j:triple:` IRI in N-Quads), every blank node is
     * written under a label no Rio writer changes ([WriterLabels]), and the parsers of this object give a label the
     * same node inside and outside a triple term ([DocumentBlankNodes]). This holds for [serializeGraph] too. (Rio's
     * own parsers, used directly, keep the labels inside a triple term as written.)
     *
     * @throws IllegalArgumentException when a statement with a quoted-triple subject holds a term Kastor cannot
     *   represent and the repository's reads are strict (lenient repositories skip it with a warning, as graph reads do).
     */
    fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val rdf4jFormat = toRdf4jFormat(format)
        val rdf4jRepo = repository as? Rdf4jRepository
            ?: throw UnsupportedOperationException("Rdf4jFormatSupport can only serialize RDF4J repositories")

        return rdf4jRepo.withConnection { connection ->
            val writer = StringWriter()
            try {
                val handler = writerFor(rdf4jFormat, writer, options)
                handler.startRDF()
                connection.namespaces.use { namespaces -> namespaces.forEach { handler.handleNamespace(it.prefix, it.name) } }
                val contexts = ArrayList<org.eclipse.rdf4j.model.Resource?>()
                contexts.add(null)
                connection.contextIDs.use { ids -> ids.forEach { contexts.add(it) } }
                var skipped = 0
                for (context in contexts) {
                    val reifies = HashSet<RdfTriple>()
                    connection.getStatements(null, null, null, false, context).use { statements ->
                        for (statement in statements) {
                            try {
                                writeView(statement, context, reifies, handler)
                            } catch (e: IllegalArgumentException) {
                                if (!rdf4jRepo.lenientRead) throw e
                                if (skipped++ == 0) LOG.warn("Skipping RDF4J statement(s) that are not valid RDF terms for Kastor; first: {} ({})", statement, e.message)
                            }
                        }
                    }
                }
                handler.endRDF()
                rdf12Text(rdf4jFormat, writer.toString())
            } catch (e: org.eclipse.rdf4j.rio.RDFHandlerException) {
                throw RdfFormatException.Generic("Failed to serialize dataset: ${e.message}", RdfErrorCode.FORMAT_SERIALIZATION_ERROR, e)
            }
        }
    }

    /** Writes [statement] of the graph [context] as the graph API reads it; [reifies] holds the `rdf:reifies` triples written. */
    private fun writeView(
        statement: Statement,
        context: org.eclipse.rdf4j.model.Resource?,
        reifies: MutableSet<RdfTriple>,
        handler: RDFHandler,
    ) {
        val reifiesIri = com.geoknoesis.kastor.rdf.vocab.RDF.reifies
        if (!Rdf4jTerms.hasQuotedSubject(statement)) {
            // Written as stored. A stored `_:r rdf:reifies <<( s p o )>>` may also be implied by another statement.
            val explicitReifies = statement.`object` is org.eclipse.rdf4j.model.Triple && statement.predicate.stringValue() == reifiesIri.value
            if (!explicitReifies || reifies.add(Rdf4jTerms.triplesOf(statement).first())) handler.handleStatement(statement)
            return
        }
        val valueFactory = org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance()
        for (triple in Rdf4jTerms.triplesOf(statement)) {
            if (triple.predicate == reifiesIri && triple.obj is TripleTerm && !reifies.add(triple)) continue
            val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
            val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
            val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
            handler.handleStatement(
                if (context == null) valueFactory.createStatement(subject, predicate, obj) else valueFactory.createStatement(subject, predicate, obj, context),
            )
        }
    }

    private val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(Rdf4jFormatSupport::class.java)

    /**
     * Parse RDF dataset data from an input stream into a Kastor repository using RDF4J (no base IRI).
     * Blank-node graph names are skolemized, see the overload with a base IRI.
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String) {
        parseDataset(repository, inputStream, format, "")
    }

    /**
     * Parse a dataset using an explicit base IRI.
     *
     * **Blank-node graph names are skolemized** (TriG `_:g { }` or `[] { }`, an N-Quads graph label `_:g`), like the
     * Jena provider does: Kastor repositories name graphs by [Iri], so a blank-node context would be stored but
     * unreachable through `listGraphs` / `getGraph`. A blank graph name becomes
     * `urn:kastor:skolem:<load>:<blank node id>` ([skolemGraphName]), the same form the Jena provider uses, with one
     * random `<load>` id (32 hex digits) per call: the same label within a document names the same graph, and
     * separate loads never share a skolem graph (blank nodes are scoped to the document). Only the graph name is
     * replaced; the same blank node used as a subject or object inside the data stays a blank node, and the id in
     * the graph name is its [org.eclipse.rdf4j.model.BNode.getID]. The mapping is stateless.
     *
     * Blank node labels are scoped to the document, also inside triple terms (see [DocumentBlankNodes]).
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
                val load = java.util.UUID.randomUUID().toString().replace("-", "")
                parser(rdf4jFormat) { statement ->
                    checkedTriples(statement, null)
                    rdf4jRepo.noteQuotedWrite(Rdf4jTerms.quotedLevel(statement.subject, statement.`object`))
                    if (statement.`object` is org.eclipse.rdf4j.model.Triple) rdf4jRepo.noteTripleValue()
                    rdf4jRepo.noteWrittenValue(statement.subject)
                    rdf4jRepo.noteWrittenValue(statement.`object`)
                    val context = when (val name = statement.context) {
                        is org.eclipse.rdf4j.model.BNode -> connection.valueFactory.createIRI(skolemGraphName(load, name.id))
                        else -> name
                    }
                    if (context != null) {
                        connection.add(statement.subject, statement.predicate, statement.`object`, context)
                    } else {
                        connection.add(statement.subject, statement.predicate, statement.`object`)
                    }
                }.parse(rdf12Input(rdf4jFormat, inputStream), baseIri)
            }
        }
    }
}
