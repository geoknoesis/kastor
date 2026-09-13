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

    override fun supportsFormat(format: String): Boolean {
        val normalized = format.uppercase().trim()
        return normalized in JenaParsing.FORMATS
    }

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
        return JenaBridge.fromJenaModel(model)
    }

    /** Compatibility API is eager so abandoning an ordinary Sequence cannot leak a producer. */
    override fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> =
        openTripleStream(object : java.io.FilterInputStream(inputStream) { override fun close() = Unit }, format)
            .use { it.toList().asSequence() }

    override fun openTripleStream(inputStream: java.io.InputStream, format: String): TripleStream {
        val lang = JenaParsing.graphLang(format)
        val parser = org.apache.jena.riot.system.AsyncParser.of(JenaParsing.parser(inputStream, lang, null)).asyncParseTriples()
        return object : TripleStream {
            private var closed = false
            private val knownIris = HashSet<String>()
            private val rows = Sequence {
                object : Iterator<RdfTriple> {
                    override fun hasNext(): Boolean {
                        check(!closed) { "Triple stream is closed" }
                        return JenaParsing.parseWithFormatErrors(format) { parser.hasNext() }
                    }
                    override fun next(): RdfTriple {
                        check(!closed) { "Triple stream is closed" }
                        return JenaTerms.fromJenaTriple(JenaParsing.parseWithFormatErrors(format) { parser.next().also { JenaParsing.validateTriple(it, knownIris) } })
                    }
                }
            }.constrainOnce()
            override fun iterator(): Iterator<RdfTriple> { check(!closed); return rows.iterator() }
            override fun close() {
                if (!closed) {
                    closed = true
                    try { inputStream.close() } finally { parser.close() }
                }
            }
        }
    }

    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String, baseIri: String?) {
        val lang = RDFLanguages.nameToLang(JenaBridge.normalizeJenaLang(format))
            ?: throw RdfFormatException.UnsupportedFormat(format, JenaParsing.FORMATS)
        val parsed = org.apache.jena.query.DatasetFactory.create()
        try {
            JenaParsing.parseWithFormatErrors(format) { JenaParsing.parser(inputStream, lang, baseIri).parse(JenaParsing.validating(org.apache.jena.riot.system.StreamRDFLib.dataset(parsed.asDatasetGraph()))) }
            repository.transaction {
                val jena = repository as? JenaRepository
                if (jena != null) {
                    val target = jena.getJenaDataset()
                    target.defaultModel.add(parsed.defaultModel)
                    parsed.listNames().forEachRemaining { target.getNamedModel(it).add(parsed.getNamedModel(it)) }
                } else {
                    editDefaultGraph().addTriples(JenaGraph(parsed.defaultModel).getTriples())
                    parsed.listNames().forEachRemaining { editGraph(Iri(it)).addTriples(JenaGraph(parsed.getNamedModel(it)).getTriples()) }
                }
            }
        } finally { parsed.close() }
    }
    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String) =
        parseDataset(repository, inputStream, format, null)
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

        private val languageTag = Regex("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$")
        private const val RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
        private const val RDF_DIR_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString"

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
                    if (!language.isNullOrEmpty()) {
                        if (!languageTag.matches(language)) throw RiotException("Invalid language tag: '$language'")
                    } else if (node.literalDatatypeURI == RDF_LANG_STRING || node.literalDatatypeURI == RDF_DIR_LANG_STRING) {
                        throw RiotException("Literal typed ${node.literalDatatypeURI} requires a language tag")
                    } else {
                        node.literalDatatypeURI?.let { validateIri(it, knownIris) }
                    }
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
