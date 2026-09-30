package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.irix.IRIxResolver
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFLanguages
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.RDFParserBuilder
import org.apache.jena.riot.RiotException

/**
 * Jena provider implementation for the RDF API.
 *
 * **Parsing contract** (shared with the RDF4J provider):
 * - Input streams are handed to the Jena parser directly (no intermediate String copy), so the
 *   document's declared encoding (e.g. RDF/XML `encoding="ISO-8859-1"`) is honoured.
 * - Without a base IRI, relative IRI references are a parse error (no implicit `file:` base).
 * - [parseGraph] rejects quad formats (TriG, N-Quads) with [RdfFormatException]: parsing them as a
 *   single graph would silently drop or merge named graphs. Use [parseDataset] instead.
 * - Syntax errors surface as [RdfFormatException].
 * - [parseDataset] **skolemizes blank-node graph names** (TriG `_:g { }`, an N-Quads graph label `_:g`) on
 *   every load path: Kastor repositories name graphs by IRI only, so each distinct blank graph name of one load
 *   becomes a fresh IRI `urn:kastor:skolem:<uuid>` ([JenaParsing.SKOLEM_GRAPH_PREFIX]). The same label within
 *   a document maps to the same graph; separate loads never share a skolem graph (blank nodes are
 *   document-scoped). The graph is then listed by `listGraphs()` and readable with `getGraph(...)`.
 */
class JenaProvider : RdfProvider {

    override val id: String = "jena"
    override val name: String = "Jena Repository"
    override val version: String = org.apache.jena.Jena.VERSION

    override fun variants(): List<RdfVariant> {
        return listOf(
            RdfVariant("memory", "In-memory store"),
            RdfVariant("memory-inference", "In-memory store with inference"),
            RdfVariant("tdb2", "TDB2 persistent store"),
            RdfVariant("tdb2-inference", "TDB2 store with inference")
        )
    }

    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        return when (variantId) {
            "memory" -> JenaRepository.MemoryRepository()
            "memory-inference" -> JenaRepository.MemoryRepositoryWithInference()
            "tdb2" -> {
                val location = config.options["location"] ?: "data"
                JenaRepository.Tdb2Repository(location)
            }
            "tdb2-inference" -> {
                val location = config.options["location"] ?: "data"
                JenaRepository.Tdb2RepositoryWithInference(location)
            }
            else -> throw IllegalArgumentException("Unsupported Jena repository variant: $variantId")
        }
    }

    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        return ProviderCapabilities(
            rdfVersion = "1.2",
            supportsTripleTerms = true,
            supportsInference = variantId?.endsWith("-inference") == true,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = true,
            // Jena models rdf:dirLangString natively (NodeFactory.createLiteralDirLang).
            supportsBaseDirection = true,
            maxMemoryUsage = Long.MAX_VALUE,
            sparqlVersion = "1.2",
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsVersionDeclaration = true,
            // generateServiceDescription is not implemented by this provider.
            supportsServiceDescription = false,
            supportedInputFormats = JenaParsing.FORMATS,
            supportedOutputFormats = JenaParsing.FORMATS // Jena supports same formats for input and output
        )
    }

    /** Preferred over RDF4J (40) and the in-core memory store (-100) when several providers support a format. */
    override val priority: Int = 50

    override fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions): String {
        return JenaBridge.toString(graph, format, options)
    }

    override fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions): String {
        // Get the underlying Jena Dataset if available
        val jenaRepo = repository as? JenaRepository
            ?: throw UnsupportedOperationException("JenaProvider can only serialize Jena repositories")
        // Serialize the same view that graph reads expose (including inferences for inference variants).
        return jenaRepo.withRead { JenaBridge.serializeDataset(jenaRepo.queryDataset(), format, options) }
    }

    override fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph =
        parseGraph(inputStream, format, null)

    override fun parseGraph(
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
    ): MutableRdfGraph {
        val lang = JenaParsing.graphLang(format)
        val model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel()
        try {
            JenaParsing.parseWithFormatErrors(format) { JenaParsing.parser(inputStream, lang, baseIri).parse(JenaParsing.validating(org.apache.jena.riot.system.StreamRDFLib.graph(model.graph))) }
        } catch (failure: Throwable) {
            model.close()
            throw failure
        }
        // Parsed data is already validated: keep strict reads.
        return JenaGraph(model)
    }

    /** Compatibility API is eager so abandoning an ordinary Sequence cannot leak a producer. */
    override fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> =
        parseStreamingWithBase(inputStream, format, null)

    override fun openTripleStream(inputStream: java.io.InputStream, format: String): TripleStream =
        openTripleStreamWithBase(inputStream, format, null)

    override fun parseStreaming(inputStream: java.io.InputStream, format: String, baseIri: String?): Sequence<RdfTriple> =
        parseStreamingWithBase(inputStream, format, baseIri)

    override fun openTripleStream(inputStream: java.io.InputStream, format: String, baseIri: String?): TripleStream =
        openTripleStreamWithBase(inputStream, format, baseIri)

    /**
     * Eager compatibility parse with a base IRI; the caller's stream is not closed.
     * Implementation target for the core `parseStreaming(inputStream, format, baseIri)` provider method.
     */
    internal fun parseStreamingWithBase(inputStream: java.io.InputStream, format: String, baseIri: String?): Sequence<RdfTriple> =
        openTripleStreamWithBase(object : java.io.FilterInputStream(inputStream) { override fun close() = Unit }, format, baseIri)
            .use { it.toList().asSequence() }

    /**
     * Lazy streaming parse resolving relative IRIs against [baseIri] (`null`: relative IRIs are a parse error).
     * Jena parses on a background thread; read-ahead is bounded to [STREAM_CHUNK_SIZE] x [STREAM_QUEUE_SIZE] triples.
     * Implementation target for the core `openTripleStream(inputStream, format, baseIri)` provider method.
     */
    internal fun openTripleStreamWithBase(inputStream: java.io.InputStream, format: String, baseIri: String?): TripleStream =
        openTripleStreamWithBase(inputStream, format, baseIri, STREAM_CLEANER::register)

    /**
     * [openTripleStreamWithBase] with an injectable cleanup registrar, so tests can trigger the cleanup of an
     * abandoned stream deterministically instead of waiting for garbage collection.
     */
    internal fun openTripleStreamWithBase(
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
        registerCleanup: (Any, Runnable) -> java.lang.ref.Cleaner.Cleanable,
    ): TripleStream {
        val lang = JenaParsing.graphLang(format)
        val parser = org.apache.jena.riot.system.AsyncParser.of(JenaParsing.parser(inputStream, lang, baseIri))
            .setChunkSize(STREAM_CHUNK_SIZE)
            .setQueueSize(STREAM_QUEUE_SIZE)
            .asyncParseTriples()
        return JenaTripleStream(StreamResources(inputStream, parser), format, registerCleanup)
    }

    /**
     * What an open stream holds: the caller's input and Jena's background parser (thread `AsyncParser`, blocked on a
     * bounded queue while nobody reads). Closing it stops and joins that thread. It must never reference the
     * [JenaTripleStream] itself, so an abandoned stream stays collectable and its cleaner can run.
     */
    internal class StreamResources(
        private val input: java.io.InputStream,
        val parser: org.apache.jena.atlas.iterator.IteratorCloseable<org.apache.jena.graph.Triple>,
    ) : Runnable {
        @Volatile var closed = false
            private set

        override fun run() {
            if (closed) return
            closed = true
            try { input.close() } catch (_: java.io.IOException) { } finally { parser.close() }
        }
    }

    /**
     * Lazy Jena triple stream. [close] stops the parser thread and closes the input; a stream abandoned without
     * [close] is closed by a [java.lang.ref.Cleaner] once it becomes unreachable, so its parser thread cannot stay
     * blocked forever.
     */
    internal class JenaTripleStream(
        internal val resources: StreamResources,
        private val format: String,
        registerCleanup: (Any, Runnable) -> java.lang.ref.Cleaner.Cleanable,
    ) : TripleStream {
        private val cleanable = registerCleanup(this, resources)
        private val knownIris = HashSet<String>()
        private val rows = Sequence {
            object : Iterator<RdfTriple> {
                override fun hasNext(): Boolean {
                    check(!resources.closed) { "Triple stream is closed" }
                    return JenaParsing.parseWithFormatErrors(format) { resources.parser.hasNext() }
                }
                override fun next(): RdfTriple {
                    check(!resources.closed) { "Triple stream is closed" }
                    return JenaTerms.fromJenaTriple(JenaParsing.parseWithFormatErrors(format) { resources.parser.next().also { JenaParsing.validateTriple(it, knownIris) } })
                }
            }
        }.constrainOnce()

        override fun iterator(): Iterator<RdfTriple> {
            check(!resources.closed) { "Triple stream is closed" }
            return rows.iterator()
        }

        override fun close() = cleanable.clean()
    }

    /**
     * Parses a dataset into [repository].
     *
     * - Jena repositories: streamed straight into one write transaction on the store (joining an enclosing transaction).
     * - Other repositories that support transactions: streamed in batches of [DATASET_BATCH_SIZE] triples per graph
     *   into one `transaction { }` of the target, so memory stays bounded and a parse failure rolls the load back.
     * - Repositories without transactions: parsed completely first, so a syntax error never leaves partial data.
     *
     * Every path skolemizes blank-node graph names the same way (see the class documentation).
     */
    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String, baseIri: String?) {
        val lang = RDFLanguages.nameToLang(JenaBridge.normalizeJenaLang(format))
            ?: throw RdfFormatException.UnsupportedFormat(format, JenaParsing.FORMATS)
        val jena = repository as? JenaRepository
        if (jena != null) {
            // Stream straight into one write transaction on the store (joining an enclosing transaction):
            // no intermediate copy of the dataset, and a parse failure rolls the whole load back.
            jena.transaction {
                JenaParsing.parseWithFormatErrors(format) {
                    JenaParsing.parser(inputStream, lang, baseIri)
                        .parse(JenaParsing.validatingDataset(org.apache.jena.riot.system.StreamRDFLib.dataset(jena.getJenaDataset().asDatasetGraph())))
                }
            }
            return
        }
        if (repository.getCapabilities().supportsTransactions) {
            repository.transaction {
                val sink = BatchingDatasetSink(this)
                JenaParsing.parseWithFormatErrors(format) { JenaParsing.parser(inputStream, lang, baseIri).parse(JenaParsing.validatingDataset(sink)) }
                sink.flushAll()
            }
            return
        }
        // Repositories without transactions: parse fully first, so a syntax error never leaves partial data behind.
        val parsed = org.apache.jena.query.DatasetFactory.create()
        try {
            JenaParsing.parseWithFormatErrors(format) { JenaParsing.parser(inputStream, lang, baseIri).parse(JenaParsing.validatingDataset(org.apache.jena.riot.system.StreamRDFLib.dataset(parsed.asDatasetGraph()))) }
            repository.transaction {
                editDefaultGraph().addTriples(JenaGraph(parsed.defaultModel).getTriples())
                parsed.listNames().forEachRemaining { editGraph(Iri(it)).addTriples(JenaGraph(parsed.getNamedModel(it)).getTriples()) }
            }
        } finally { parsed.close() }
    }
    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String) =
        parseDataset(repository, inputStream, format, null)

    /**
     * Parser sink that converts quads as they are parsed and adds them to [target] (inside its transaction) in
     * batches of [DATASET_BATCH_SIZE] triples per graph, so a load never buffers a copy of the whole dataset.
     */
    private class BatchingDatasetSink(private val target: RdfRepository) : org.apache.jena.riot.system.StreamRDFBase() {
        private val pending = LinkedHashMap<String?, MutableList<RdfTriple>>()

        override fun triple(triple: org.apache.jena.graph.Triple) = add(null, triple)

        // Blank-node graph names were skolemized by JenaParsing.validatingDataset before reaching this sink.
        override fun quad(quad: org.apache.jena.sparql.core.Quad) =
            add(if (quad.isDefaultGraph) null else quad.graph.uri, quad.asTriple())

        private fun add(graph: String?, triple: org.apache.jena.graph.Triple) {
            val batch = pending.getOrPut(graph) { ArrayList(DATASET_BATCH_SIZE) }
            batch.add(JenaTerms.fromJenaTriple(triple))
            if (batch.size >= DATASET_BATCH_SIZE) flush(graph)
        }

        private fun flush(graph: String?) {
            val batch = pending.remove(graph) ?: return
            if (graph == null) target.editDefaultGraph().addTriples(batch) else target.editGraph(Iri(graph)).addTriples(batch)
        }

        fun flushAll() = pending.keys.toList().forEach(::flush)
    }

    internal companion object {
        /** Triples per batch handed from Jena's background parser to the consumer. */
        const val STREAM_CHUNK_SIZE = 1_000

        /** Batches buffered ahead of the consumer. */
        const val STREAM_QUEUE_SIZE = 4

        /** Triples per graph added to a foreign repository at a time by [parseDataset]. */
        const val DATASET_BATCH_SIZE = 1_000

        /** Closes triple streams that were abandoned without `close()`. */
        val STREAM_CLEANER: java.lang.ref.Cleaner = java.lang.ref.Cleaner.create()
    }
}

/** Shared Jena parsing helpers: format resolution, base-IRI policy and error mapping. */
internal object JenaParsing {
        val FORMATS = listOf(
            "TURTLE", "TTL", "TURTLE-1.2", "TURTLESTAR",
            "JSON-LD", "JSONLD", "JSON-LD-1.2",
            "RDF/XML", "RDFXML", "XML",
            "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2",
            "TRIG", "TRI-G", "TRIG-1.2", "TRIGSTAR",
            "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2",
        )

        /** Resolves a graph (triple) format; quad formats are rejected instead of being silently flattened. */
        fun graphLang(format: String): Lang {
            val lang = RDFLanguages.nameToLang(JenaBridge.normalizeJenaLang(format))
                ?: throw RdfFormatException.UnsupportedFormat(format, JenaParsing.FORMATS)
            if (lang == Lang.TRIG || lang == Lang.NQUADS) {
                throw RdfFormatException.Generic(
                    "Format '$format' is a quad (dataset) format; parsing it as a single graph would drop or merge " +
                        "named graphs. Use parseDataset(...) instead.",
                    RdfErrorCode.FORMAT_PARSE_ERROR,
                )
            }
            return lang
        }

        /**
         * Builds a spec-strict parser with the shared base-IRI policy: without a base, relative IRIs are errors.
         * (Jena's default non-strict mode accepts e.g. a missing final DOT or bare collections.)
         */
        fun parser(inputStream: java.io.InputStream, lang: Lang, baseIri: String?): RDFParserBuilder {
            val builder = RDFParser.source(inputStream).lang(lang).strict(true)
            return if (baseIri != null) builder.base(baseIri)
            else builder.resolver(IRIxResolver.create().noBase().allowRelative(false).build())
        }

        /**
         * Rejects terms that Jena's parser tolerates but that are not valid RDF (and cannot be represented
         * by Kastor terms): IRIs Kastor's [Iri] rejects, malformed language tags, and `rdf:langString` /
         * `rdf:dirLangString` literals without a language tag. Throws [RiotException].
         */
        fun validateTriple(triple: org.apache.jena.graph.Triple, knownIris: MutableSet<String>) {
            validateNode(triple.subject, knownIris)
            validateNode(triple.predicate, knownIris)
            validateNode(triple.`object`, knownIris)
        }

        private fun validateNode(node: org.apache.jena.graph.Node, knownIris: MutableSet<String>) {
            when {
                node.isURI -> validateIri(node.uri, knownIris)
                node.isTripleTerm -> validateTriple(node.triple, knownIris)
                node.isLiteral -> {
                    val language = node.literalLanguage
                    LiteralValidation.problem(node.literalDatatypeURI, language, node.literalBaseDirection != null)
                        ?.let { throw RiotException(it) }
                    if (language.isNullOrEmpty()) node.literalDatatypeURI?.let { validateIri(it, knownIris) }
                }
            }
        }

        private fun validateIri(iri: String, knownIris: MutableSet<String>) {
            if (iri in knownIris) return
            try {
                Iri(iri)
            } catch (e: IllegalArgumentException) {
                throw RiotException(e.message ?: "Invalid IRI: $iri")
            }
            if (knownIris.size > 100_000) knownIris.clear()
            knownIris.add(iri)
        }

        /** Prefix of the IRIs that replace blank-node graph names when a dataset is loaded into a repository. */
        const val SKOLEM_GRAPH_PREFIX = "urn:kastor:skolem:"

        /**
         * [validating] for loads into a Kastor repository: additionally skolemizes blank-node graph names, which
         * repositories (graphs named by [Iri]) cannot represent. Each distinct blank graph name of this parse gets
         * a fresh `urn:kastor:skolem:<uuid>` IRI.
         */
        fun validatingDataset(target: org.apache.jena.riot.system.StreamRDF): org.apache.jena.riot.system.StreamRDF =
            validating(object : org.apache.jena.riot.system.StreamRDFWrapper(target) {
                private val skolemized = HashMap<org.apache.jena.graph.Node, org.apache.jena.graph.Node>()
                override fun quad(quad: org.apache.jena.sparql.core.Quad) {
                    if (!quad.graph.isBlank) return super.quad(quad)
                    val name = skolemized.getOrPut(quad.graph) {
                        org.apache.jena.graph.NodeFactory.createURI(SKOLEM_GRAPH_PREFIX + java.util.UUID.randomUUID())
                    }
                    super.quad(org.apache.jena.sparql.core.Quad.create(name, quad.asTriple()))
                }
            })

        /** Wraps a parser sink so every triple/quad is validated before it is stored. */
        fun validating(target: org.apache.jena.riot.system.StreamRDF): org.apache.jena.riot.system.StreamRDF =
            object : org.apache.jena.riot.system.StreamRDFWrapper(target) {
                private val knownIris = HashSet<String>()
                override fun triple(triple: org.apache.jena.graph.Triple) {
                    validateTriple(triple, knownIris)
                    super.triple(triple)
                }
                override fun quad(quad: org.apache.jena.sparql.core.Quad) {
                    if (!quad.isDefaultGraph) validateNode(quad.graph, knownIris)
                    validateTriple(quad.asTriple(), knownIris)
                    super.quad(quad)
                }
            }

        inline fun <T> parseWithFormatErrors(format: String, block: () -> T): T = try {
            block()
        } catch (e: RiotException) {
            throw RdfFormatException.Generic("Failed to parse $format data: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
        } catch (e: org.apache.jena.atlas.RuntimeIOException) {
            throw RdfFormatException.Generic("Failed to read $format data: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
        }
    }
