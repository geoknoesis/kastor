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
     * The blank nodes of one parsed document: every label of the document gets an id of the form
     * `genid-<document, 32 hex digits>-<label>` (Rio's own scheme; a label longer than 32 characters is replaced by
     * its MD5 digest), the same id wherever the label occurs.
     *
     * Rio alone does not do that: it renames the labels it reads as terms of a statement, but a triple term that is
     * written as an `urn:rdf4j:triple:` IRI (the form Rio's writers use in every RDF 1.1 syntax, so the form
     * [serializeGraph] and [serializeDataset] produce) is decoded with its labels as written. A blank node, or a
     * reifier, that occurs both inside and outside a triple term (an annotation on an annotation) would come back as
     * two nodes, and the label inside the triple term would be shared by every document parsed. The parsers of this
     * object therefore keep the labels of the document ([parser]) and rename them all here.
     */
    private class DocumentBlankNodes {
        private val prefix = "genid-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-"
        private val values = org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance()
        private val renamed = HashMap<String, org.eclipse.rdf4j.model.BNode>()

        private fun node(label: org.eclipse.rdf4j.model.BNode): org.eclipse.rdf4j.model.BNode = renamed.getOrPut(label.id) {
            val id = label.id
            val suffix = if (id.length <= MAX_LABEL_LENGTH) id else {
                java.security.MessageDigest.getInstance("MD5").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }
            values.createBNode(prefix + suffix)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <V : org.eclipse.rdf4j.model.Value?> value(value: V): V = when (value) {
            is org.eclipse.rdf4j.model.BNode -> node(value) as V
            is org.eclipse.rdf4j.model.Triple -> {
                val subject = value(value.subject)
                val obj = value(value.`object`)
                if (subject === value.subject && obj === value.`object`) value else values.createTriple(subject, value.predicate, obj) as V
            }
            else -> value
        }

        /** [statement] with the blank nodes of this document; [statement] itself when it has none. */
        fun statement(statement: Statement): Statement {
            val subject = value(statement.subject)
            val obj = value(statement.`object`)
            val context = value(statement.context)
            if (subject === statement.subject && obj === statement.`object` && context === statement.context) return statement
            return if (context == null) values.createStatement(subject, statement.predicate, obj)
            else values.createStatement(subject, statement.predicate, obj, context)
        }

        private companion object {
            const val MAX_LABEL_LENGTH = 32
        }
    }

    /**
     * Blank node labels that every Rio writer writes as they are, inside and outside a triple term.
     *
     * Rio's writers rewrite a blank node id that is not a plain label on their own (the N-Triples / N-Quads writer
     * replaces every character that is not an ASCII letter or digit by its hex code, so `a-b` is written `_:a2db`),
     * but not inside a triple term, which they write as an `urn:rdf4j:triple:` IRI holding the ids unchanged. The
     * blank node `a-b` would be written under two labels. The serializers of this object therefore give every blank
     * node a label that no writer changes:
     * - an id of ASCII letters and digits that starts with a letter and has no `Z` is its own label;
     * - any other id is written as `Z` followed by its characters, with every byte (of its UTF-8 form) that is not an
     *   ASCII letter other than `Z`, or a digit, written as `Z` and two hex digits.
     *
     * The mapping is injective (the two cases cannot produce the same label, and the escape is reversible), so two
     * blank nodes are never merged.
     */
    private object WriterLabels {
        private val values = org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance()

        private fun plain(c: Char): Boolean = (c in 'A'..'Y') || (c in 'a'..'z') || (c in '0'..'9')

        fun label(id: String): String {
            if (id.isNotEmpty() && id[0] !in '0'..'9' && id.all(::plain)) return id
            val out = StringBuilder(id.length + 16).append('Z')
            for (byte in id.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (byte >= 0 && plain(c)) out.append(c) else out.append('Z').append("%02x".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }

        @Suppress("UNCHECKED_CAST")
        fun <V : org.eclipse.rdf4j.model.Value?> value(value: V): V = when (value) {
            is org.eclipse.rdf4j.model.BNode -> label(value.id).let { if (it == value.id) value else values.createBNode(it) as V }
            is org.eclipse.rdf4j.model.Triple -> {
                val subject = value(value.subject)
                val obj = value(value.`object`)
                if (subject === value.subject && obj === value.`object`) value else values.createTriple(subject, value.predicate, obj) as V
            }
            else -> value
        }

        /** [statement] with writer-safe blank node labels; [statement] itself when nothing changes. */
        fun statement(statement: Statement): Statement {
            val subject = value(statement.subject)
            val obj = value(statement.`object`)
            val context = value(statement.context)
            if (subject === statement.subject && obj === statement.`object` && context === statement.context) return statement
            return if (context == null) values.createStatement(subject, statement.predicate, obj)
            else values.createStatement(subject, statement.predicate, obj, context)
        }
    }

    /**
     * A Rio parser for one document that feeds [handle] its statements with document-scoped blank nodes (see
     * [DocumentBlankNodes]). Rio keeps the labels as written (`PRESERVE_BNODE_IDS`), and they are renamed here, so
     * that labels inside triple terms get the same treatment as the others.
     */
    private fun parser(format: RDFFormat, handle: (Statement) -> Unit): org.eclipse.rdf4j.rio.RDFParser {
        val parser = Rio.createParser(format)
        parser.parserConfig.set(org.eclipse.rdf4j.rio.helpers.BasicParserSettings.PRESERVE_BNODE_IDS, true)
        val nodes = DocumentBlankNodes()
        parser.setRDFHandler(object : AbstractRDFHandler() {
            override fun handleStatement(statement: Statement) = handle(nodes.statement(statement))
        })
        return parser
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
        val seen = HashSet<org.eclipse.rdf4j.model.Triple>()
        formatErrors("$format data") {
            parser(rdf4jFormat) { statement -> triples.addAll(checkedTriples(statement, seen)) }.parse(inputStream, baseIri)
        }
        return com.geoknoesis.kastor.rdf.provider.MemoryGraph(triples)
    }

    /**
     * Converts and validates a parsed statement; terms Kastor cannot represent become Rio parse errors.
     * Rio reads RDF 1.2 reified-triple syntax (`<< s p o >> :q :z`, annotations) as RDF-star quoted-triple
     * subjects; those map to the RDF 1.2 reified form (see [Rdf4jTerms.triplesOf]), with each `rdf:reifies`
     * triple emitted once per [seen] set.
     */
    private fun checkedTriples(statement: Statement, seen: MutableSet<org.eclipse.rdf4j.model.Triple>?): List<RdfTriple> = try {
        Rdf4jTerms.triplesOf(statement, seen).onEach { Rdf4jTerms.requireWellFormed(it.obj) }
    } catch (e: IllegalArgumentException) {
        throw RDFParseException("Invalid RDF term: ${e.message}").also { it.initCause(e) }
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
                    parser(format) { statement -> checkedTriples(statement, seen).forEach { offer(state, it, hooks) } }.parse(state.input, baseIri)
                    terminal = END
                } catch (_: Cancelled) {
                    // closed: the close action already woke the consumer
                } catch (e: Throwable) {
                    terminal = try { Failure(e) } catch (_: Throwable) { PRODUCER_DIED }
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
     * an annotated triple) stays one node when the document is parsed by [parseDataset] / [parseGraph]: Rio writes a
     * triple term as an `urn:rdf4j:triple:` IRI with the labels of the blank nodes inside it, every blank node is
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
                writer.toString()
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
                }.parse(inputStream, baseIri)
            }
        }
    }
}
