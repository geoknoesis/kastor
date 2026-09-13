package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.graph.Graph
import org.apache.jena.graph.impl.WrappedGraph
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.reasoner.ReasonerRegistry
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFWriter
import org.apache.jena.riot.system.PrefixMap
import org.apache.jena.riot.system.PrefixMapFactory
import org.apache.jena.shared.PrefixMapping
import org.apache.jena.shared.impl.PrefixMappingImpl
import org.apache.jena.sparql.core.DatasetGraphWrapper
import org.apache.jena.riot.RDFFormat as JenaRDFFormat
import java.io.StringWriter

/**
 * Bridge utilities for converting between Jena and Kastor RDF APIs.
 *
 * This class provides convenient methods to convert Jena Model/Graph objects
 * to Kastor RdfGraph objects and vice versa, enabling seamless interoperability
 * between the two RDF libraries.
 */
object JenaBridge {

    /**
     * Converts a Jena Model to a Kastor RdfGraph.
     *
     * @param model The Jena Model to convert
     * @return A Kastor RdfGraph that wraps the Jena Model
     */
    fun fromJenaModel(model: Model): MutableRdfGraph {
        return JenaGraph(model)
    }

    /**
     * Converts a Jena Graph to a Kastor RdfGraph.
     *
     * @param graph The Jena Graph to convert
     * @return A Kastor RdfGraph that wraps the Jena Graph
     */
    fun fromJenaGraph(graph: Graph): MutableRdfGraph {
        val model = ModelFactory.createModelForGraph(graph)
        return JenaGraph(model)
    }

    /**
     * Converts a Kastor RdfGraph to a Jena Model.
     *
     * - A standalone Jena-backed graph (created by [fromJenaModel] / [fromJenaGraph] or a Jena parser)
     *   returns its wrapped model itself (no copy).
     * - A graph that belongs to a [JenaRepository] returns a **detached copy** of what the graph's
     *   reads expose (including inferences for inference variants): the live store model is only
     *   valid inside repository transactions and must not be mutated behind the repository's back.
     * - Any other graph is copied into a new default model.
     *
     * Use [copyToJenaModel] when an independent copy is always required.
     *
     * @param rdfGraph The Kastor RdfGraph to convert
     * @return A Jena Model containing the same triples
     */
    fun toJenaModel(rdfGraph: RdfGraph): Model {
        return if (rdfGraph is JenaGraph && !rdfGraph.isRepositoryBacked) rdfGraph.model else copyToJenaModel(rdfGraph)
    }

    /**
     * Copies the triples visible through [rdfGraph] into a new, independent default Jena Model.
     * The caller owns (and should close) the returned model.
     */
    fun copyToJenaModel(rdfGraph: RdfGraph): Model {
        val model = ModelFactory.createDefaultModel()
        try {
            if (rdfGraph is JenaGraph) {
                rdfGraph.read { view -> model.add(view) }
            } else {
                val target = model.graph
                rdfGraph.getTriples().forEach { target.add(JenaTerms.toJenaTriple(it)) }
            }
            return model
        } catch (failure: Throwable) {
            model.close()
            throw failure
        }
    }

    /**
     * Converts a Kastor RdfGraph to a Jena Graph.
     *
     * @param rdfGraph The Kastor RdfGraph to convert
     * @return A Jena Graph containing the same triples
     */
    fun toJenaGraph(rdfGraph: RdfGraph): Graph {
        return toJenaModel(rdfGraph).graph
    }

    /**
     * Creates a new empty Jena Model and converts it to a Kastor RdfGraph.
     *
     * @return A new empty Kastor RdfGraph backed by a Jena Model
     */
    fun createEmptyModel(): MutableRdfGraph {
        return fromJenaModel(ModelFactory.createDefaultModel())
    }

    /**
     * Creates a new empty Jena Graph and converts it to a Kastor RdfGraph.
     *
     * @return A new empty Kastor RdfGraph backed by a Jena Graph
     */
    fun createEmptyGraph(): MutableRdfGraph {
        val model = ModelFactory.createDefaultModel()
        return fromJenaGraph(model.graph)
    }

    /**
     * Creates a Jena Model with RDFS inference and converts it to a Kastor RdfGraph.
     *
     * @return A Kastor RdfGraph backed by a Jena Model with RDFS inference
     */
    fun createInferenceModel(): MutableRdfGraph {
        val model = ModelFactory.createRDFSModel(ModelFactory.createDefaultModel())
        return fromJenaModel(model)
    }

    /**
     * Creates a Jena Model with OWL inference and converts it to a Kastor RdfGraph.
     *
     * @return A Kastor RdfGraph backed by a Jena Model with OWL inference
     */
    fun createOwlInferenceModel(): MutableRdfGraph {
        val baseModel = ModelFactory.createDefaultModel()
        val infModel = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), baseModel)
        return fromJenaModel(infModel)
    }

    /**
     * Loads RDF data from a string into a Jena Model and converts it to a Kastor RdfGraph.
     *
     * @param rdfData The RDF data as a string
     * @param format The RDF format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @return A Kastor RdfGraph containing the loaded data
     */
    fun fromString(rdfData: String, format: String = "TURTLE"): MutableRdfGraph {
        return JenaProvider().parseGraph(rdfData.byteInputStream(), format)
    }

    /**
     * Normalises a Kastor format alias (including the RDF 1.2 spellings such as
     * `TURTLE-1.2`) to the language string Jena expects in `Model.read(...)`.
     */
    internal fun normalizeJenaLang(format: String): String = when (format.uppercase().trim()) {
        "TURTLE", "TTL", "TURTLE-1.2", "TURTLE12", "TURTLESTAR" -> "TURTLE"
        "RDF/XML", "RDFXML", "XML" -> "RDF/XML"
        "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2", "NTRIPLES12" -> "N-TRIPLES"
        "JSON-LD", "JSONLD", "JSON-LD-1.2", "JSONLD12" -> "JSON-LD"
        "TRIG", "TRI-G", "TRIG-1.2", "TRIG12", "TRIGSTAR" -> "TRIG"
        "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2", "NQUADS12" -> "N-QUADS"
        else -> format
    }

    /**
     * Loads RDF data from a file into a Jena Model and converts it to a Kastor RdfGraph.
     *
     * @param filePath The path to the RDF file
     * @param format The RDF format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @return A Kastor RdfGraph containing the loaded data
     */
    fun fromFile(filePath: String, format: String = "TURTLE"): MutableRdfGraph {
        val model = ModelFactory.createDefaultModel()
        model.read(filePath, format)
        return fromJenaModel(model)
    }

    /**
     * Loads RDF data from a URL into a Jena Model and converts it to a Kastor RdfGraph.
     *
     * @param url The URL to load RDF data from
     * @param format The RDF format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @return A Kastor RdfGraph containing the loaded data
     */
    fun fromUrl(url: String, format: String = "TURTLE"): MutableRdfGraph {
        val model = ModelFactory.createDefaultModel()
        model.read(url, format)
        return fromJenaModel(model)
    }

    /**
     * Serializes a Kastor RdfGraph to a string in the specified format.
     *
     * [SerializationOptions.prefixMappings] are applied through a writer-local prefix mapping and
     * never modify the graph's own prefixes. [SerializationOptions.baseUri] is emitted as the
     * document base (`BASE`) for Turtle; formats without a base declaration ignore it so their
     * output never contains unresolvable relative IRIs. Repository-backed graphs are read inside a
     * repository read transaction, through the same view as [RdfGraph.getTriples].
     *
     * @param rdfGraph The Kastor RdfGraph to serialize
     * @param format The output format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @param options Serialization options (optional)
     * @return The serialized RDF data as a string
     */
    fun toString(rdfGraph: RdfGraph, format: String = "TURTLE", options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val lang = when (format.uppercase().trim()) {
            "TURTLE", "TTL", "TURTLE-1.2", "TURTLE12", "TURTLESTAR" -> Lang.TURTLE
            "RDF/XML", "RDFXML", "XML" -> Lang.RDFXML
            "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2", "NTRIPLES12" -> Lang.NTRIPLES
            "JSON-LD", "JSONLD", "JSON-LD-1.2", "JSONLD12" -> Lang.JSONLD
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
        return withGraphView(rdfGraph) { graph ->
            val prefixes: PrefixMapping = PrefixMappingImpl()
                .setNsPrefixes(graph.prefixMapping)
                .setNsPrefixes(options.prefixMappings)
            val writerGraph = object : WrappedGraph(graph) {
                override fun getPrefixMapping(): PrefixMapping = prefixes
            }
            val writer = RDFWriter.source(writerGraph).lang(lang)
            if (lang == Lang.TURTLE) options.baseUri?.let { writer.base(it) }
            writer.asString()
        }
    }

    /** Runs [block] on a Jena view of [rdfGraph] without mutating or leaking store models. */
    private fun <T> withGraphView(rdfGraph: RdfGraph, block: (Graph) -> T): T = when {
        rdfGraph is JenaGraph -> rdfGraph.read { view -> block(view.graph) }
        else -> copyToJenaModel(rdfGraph).let { copy -> try { block(copy.graph) } finally { copy.close() } }
    }

    /**
     * Checks if a Kastor RdfGraph is backed by a Jena Model.
     *
     * @param rdfGraph The Kastor RdfGraph to check
     * @return true if the graph is backed by Jena, false otherwise
     */
    fun isJenaBacked(rdfGraph: RdfGraph): Boolean {
        return rdfGraph is JenaGraph
    }

    /**
     * Gets the underlying Jena Model from a Kastor RdfGraph.
     *
     * For repository-backed graphs this is the live asserted store model: only use it inside a
     * repository transaction and never mutate it directly.
     *
     * @param rdfGraph The Kastor RdfGraph
     * @return The underlying Jena Model, or null if not Jena-backed
     */
    fun getJenaModel(rdfGraph: RdfGraph): Model? {
        return if (rdfGraph is JenaGraph) rdfGraph.model else null
    }

    /**
     * Gets the underlying Jena Graph from a Kastor RdfGraph.
     *
     * @param rdfGraph The Kastor RdfGraph
     * @return The underlying Jena Graph, or null if not Jena-backed
     */
    fun getJenaGraph(rdfGraph: RdfGraph): Graph? {
        return if (rdfGraph is JenaGraph) rdfGraph.model.graph else null
    }

    /**
     * Serializes a Jena Dataset to a string in the specified quad format.
     *
     * Prefixes and base are applied to a writer-local view; the dataset's own prefixes are not modified.
     *
     * @param dataset The Jena Dataset to serialize
     * @param format The output format (e.g., "TRIG", "N-QUADS")
     * @param options Serialization options (optional)
     * @return The serialized RDF dataset as a string
     */
    fun serializeDataset(dataset: Dataset, format: String = "TRIG", options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val jenaFormat = when (format.uppercase().trim()) {
            "TRIG", "TRI-G", "TRIG-1.2", "TRIG12", "TRIGSTAR" -> JenaRDFFormat.TRIG
            "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2", "NQUADS12" -> JenaRDFFormat.NQUADS
            else -> throw IllegalArgumentException("Unsupported quad format: $format. Supported: TRIG, N-QUADS")
        }
        val source = dataset.asDatasetGraph()
        val prefixes: PrefixMap = PrefixMapFactory.create(source.prefixes()).also { map ->
            options.prefixMappings.forEach { (prefix, uri) -> map.add(prefix, uri) }
        }
        val view = object : DatasetGraphWrapper(source) {
            override fun prefixes(): PrefixMap = prefixes
        }
        val writer = RDFWriter.source(view).format(jenaFormat)
        if (jenaFormat == JenaRDFFormat.TRIG) options.baseUri?.let { writer.base(it) }
        return writer.asString()
    }

    /**
     * Parses RDF dataset data from an input stream into a Jena Dataset.
     *
     * @param inputStream The input stream containing RDF dataset data
     * @param format The RDF format (e.g., "TRIG", "N-QUADS")
     * @return A Jena Dataset containing the loaded data
     */
    fun parseDatasetFromStream(inputStream: java.io.InputStream, format: String = "TRIG"): Dataset {
        val lang = when (format.uppercase().trim()) {
            "TRIG", "TRI-G", "TRIG-1.2", "TRIG12", "TRIGSTAR" -> org.apache.jena.riot.Lang.TRIG
            "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2", "NQUADS12" -> org.apache.jena.riot.Lang.NQUADS
            else -> throw IllegalArgumentException("Unsupported quad format: $format. Supported: TRIG, N-QUADS")
        }
        val dataset = DatasetFactory.create()
        JenaParsing.parseWithFormatErrors(format) { RDFDataMgr.read(dataset, inputStream, lang) }
        return dataset
    }
}

/**
 * Extension functions for convenient Jena-Kastor interoperability.
 */

/**
 * Converts a Jena Model to a Kastor RdfGraph.
 */
fun Model.toKastorGraph(): MutableRdfGraph = JenaBridge.fromJenaModel(this)

/**
 * Converts a Jena Graph to a Kastor RdfGraph.
 */
fun Graph.toKastorGraph(): MutableRdfGraph = JenaBridge.fromJenaGraph(this)

/**
 * Converts a Kastor RdfGraph to a Jena Model.
 */
fun RdfGraph.toJenaModel(): Model = JenaBridge.toJenaModel(this)

/**
 * Converts a Kastor RdfGraph to a Jena Graph.
 */
fun RdfGraph.toJenaGraph(): Graph = JenaBridge.toJenaGraph(this)

/**
 * Checks if a Kastor RdfGraph is backed by Jena.
 */
fun RdfGraph.isJenaBacked(): Boolean = JenaBridge.isJenaBacked(this)

/**
 * Gets the underlying Jena Model from a Kastor RdfGraph.
 */
fun RdfGraph.getJenaModel(): Model? = JenaBridge.getJenaModel(this)

/**
 * Gets the underlying Jena Graph from a Kastor RdfGraph.
 */
fun RdfGraph.getJenaGraph(): Graph? = JenaBridge.getJenaGraph(this)

/**
 * @deprecated Use the unified serialization API: `graph.serialize(RdfFormat.TURTLE)` instead.
 * This Jena-specific extension is kept for backward compatibility but may be removed in future versions.
 * The unified API in `rdf-core` automatically uses the best available provider.
 */
@Deprecated(
    message = "Use the unified serialization API: graph.serialize(RdfFormat.TURTLE)",
    replaceWith = ReplaceWith("serialize(RdfFormat.fromStringOrThrow(format))"),
    level = DeprecationLevel.WARNING
)
fun RdfGraph.serialize(format: String = "TURTLE"): String = JenaBridge.toString(this, format)
