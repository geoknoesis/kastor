package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*

/**
 * Jena provider implementation for the RDF API.
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
        val formats = listOf(
            "TURTLE", "TTL", "TURTLE-1.2", "TURTLESTAR",
            "JSON-LD", "JSONLD", "JSON-LD-1.2",
            "RDF/XML", "RDFXML", "XML",
            "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2",
            "TRIG", "TRI-G", "TRIG-1.2", "TRIGSTAR",
            "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2",
        )
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
            supportsServiceDescription = true,
            supportedInputFormats = formats,
            supportedOutputFormats = formats // Jena supports same formats for input and output
        )
    }
    
    override fun supportsFormat(format: String): Boolean {
        val normalized = format.uppercase().trim()
        return normalized in getCapabilities(null).supportedInputFormats
    }
    
    override fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions): String {
        return JenaBridge.toString(graph, format, options)
    }
    
    override fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions): String {
        // Get the underlying Jena Dataset if available
        val jenaRepo = repository as? JenaRepository
            ?: throw UnsupportedOperationException("JenaProvider can only serialize Jena repositories")
        
        val dataset = jenaRepo.getJenaDataset()
        return jenaRepo.withRead { JenaBridge.serializeDataset(dataset, format, options) }
    }
    
    override fun parseGraph(inputStream: java.io.InputStream, format: String): MutableRdfGraph {
        val data = inputStream.readBytes().toString(Charsets.UTF_8)
        return JenaBridge.fromString(data, format)
    }

    override fun parseGraph(
        inputStream: java.io.InputStream,
        format: String,
        baseIri: String?,
    ): MutableRdfGraph {
        if (baseIri == null) return parseGraph(inputStream, format)
        val data = inputStream.readBytes().toString(Charsets.UTF_8)
        // Jena's Model.read uses an empty string as the default base; passing
        // the manifest-supplied base lets relative IRIs resolve correctly.
        val model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel()
        model.read(data.byteInputStream(), baseIri, JenaBridge.normalizeJenaLang(format))
        return JenaBridge.fromJenaModel(model)
    }

    /** Compatibility API is eager so abandoning an ordinary Sequence cannot leak a producer. */
    override fun parseStreaming(inputStream: java.io.InputStream, format: String): Sequence<RdfTriple> =
        openTripleStream(object : java.io.FilterInputStream(inputStream) { override fun close() = Unit }, format)
            .use { it.toList().asSequence() }

    override fun openTripleStream(inputStream: java.io.InputStream, format: String): TripleStream {
        val lang = org.apache.jena.riot.RDFLanguages.nameToLang(JenaBridge.normalizeJenaLang(format))
            ?: throw IllegalArgumentException("Unknown RDF format $format")
        val parser = org.apache.jena.riot.system.AsyncParser.asyncParseTriples(inputStream, lang, null)
        val model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel()
        return object : TripleStream {
            private var closed = false
            private val rows = parser.asSequence().map { triple ->
                check(!closed) { "Triple stream is closed" }
                RdfTriple(JenaTerms.fromNode(model.asRDFNode(triple.subject)) as RdfResource,
                    Iri(triple.predicate.uri), JenaTerms.fromNode(model.asRDFNode(triple.`object`)))
            }.constrainOnce()
            override fun iterator(): Iterator<RdfTriple> { check(!closed); return rows.iterator() }
            override fun close() {
                if (!closed) {
                    closed = true
                    try { inputStream.close() } finally { try { parser.close() } finally { model.close() } }
                }
            }
        }
    }

    override fun parseDataset(repository: RdfRepository, inputStream: java.io.InputStream, format: String, baseIri: String?) {
        val parsed = org.apache.jena.query.DatasetFactory.create()
        try {
            val parser = org.apache.jena.riot.RDFParser.source(inputStream)
                .lang(org.apache.jena.riot.RDFLanguages.nameToLang(JenaBridge.normalizeJenaLang(format)))
            if (baseIri != null) parser.base(baseIri)
            parser.parse(parsed)
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
