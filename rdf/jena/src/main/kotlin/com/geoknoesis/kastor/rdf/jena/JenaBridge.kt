package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.graph.Graph

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
    fun fromJenaModel(model: Model): RdfGraph {
        return JenaGraph(model)
    }

    /**
     * Converts a Jena Graph to a Kastor RdfGraph.
     * 
     * @param graph The Jena Graph to convert
     * @return A Kastor RdfGraph that wraps the Jena Graph
     */
    fun fromJenaGraph(graph: Graph): RdfGraph {
        val model = ModelFactory.createModelForGraph(graph)
        return JenaGraph(model)
    }

    /**
     * Converts a Kastor RdfGraph to a Jena Model.
     * 
     * @param rdfGraph The Kastor RdfGraph to convert
     * @return A Jena Model containing the same triples
     */
    fun toJenaModel(rdfGraph: RdfGraph): Model {
        return when (rdfGraph) {
            is JenaGraph -> rdfGraph.model
            else -> {
                // For non-Jena graphs, create a new model and copy triples
                val model = ModelFactory.createDefaultModel()
                rdfGraph.getTriples().forEach { triple ->
                    val subject = JenaTerms.toResource(triple.subject)
                    val predicate = JenaTerms.toProperty(triple.predicate)
                    val obj = JenaTerms.toNode(triple.obj)
                    model.add(subject, predicate, obj)
                }
                model
            }
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
    fun createEmptyModel(): RdfGraph {
        return fromJenaModel(ModelFactory.createDefaultModel())
    }

    /**
     * Creates a new empty Jena Graph and converts it to a Kastor RdfGraph.
     * 
     * @return A new empty Kastor RdfGraph backed by a Jena Graph
     */
    fun createEmptyGraph(): RdfGraph {
        val model = ModelFactory.createDefaultModel()
        return fromJenaGraph(model.graph)
    }

    /**
     * Creates a Jena Model with RDFS inference and converts it to a Kastor RdfGraph.
     * 
     * @return A Kastor RdfGraph backed by a Jena Model with RDFS inference
     */
    fun createInferenceModel(): RdfGraph {
        val model = ModelFactory.createRDFSModel(ModelFactory.createDefaultModel())
        return fromJenaModel(model)
    }

    /**
     * Creates a Jena Model with OWL inference and converts it to a Kastor RdfGraph.
     * 
     * @return A Kastor RdfGraph backed by a Jena Model with OWL inference
     */
    fun createOwlInferenceModel(): RdfGraph {
        val model = ModelFactory.createOntologyModel()
        return fromJenaModel(model)
    }

    /**
     * Loads RDF data from a string into a Jena Model and converts it to a Kastor RdfGraph.
     * 
     * @param rdfData The RDF data as a string
     * @param format The RDF format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @return A Kastor RdfGraph containing the loaded data
     */
    fun fromString(rdfData: String, format: String = "TURTLE"): RdfGraph {
        val model = ModelFactory.createDefaultModel()
        val inputStream = rdfData.byteInputStream()
        model.read(inputStream, null, format)
        return fromJenaModel(model)
    }

    /**
     * Loads RDF data from a file into a Jena Model and converts it to a Kastor RdfGraph.
     * 
     * @param filePath The path to the RDF file
     * @param format The RDF format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @return A Kastor RdfGraph containing the loaded data
     */
    fun fromFile(filePath: String, format: String = "TURTLE"): RdfGraph {
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
    fun fromUrl(url: String, format: String = "TURTLE"): RdfGraph {
        val model = ModelFactory.createDefaultModel()
        model.read(url, format)
        return fromJenaModel(model)
    }

    /**
     * Serializes a Kastor RdfGraph to a string in the specified format.
     * 
     * @param rdfGraph The Kastor RdfGraph to serialize
     * @param format The output format (e.g., "TURTLE", "RDF/XML", "JSON-LD")
     * @return The serialized RDF data as a string
     */
    fun toString(rdfGraph: RdfGraph, format: String = "TURTLE"): String {
        val model = toJenaModel(rdfGraph)
        return when (format.uppercase()) {
            "TURTLE", "TTL" -> {
                val stringWriter = java.io.StringWriter()
                model.write(stringWriter, "TURTLE")
                stringWriter.toString()
            }
            "RDF/XML", "RDFXML", "XML" -> {
                val stringWriter = java.io.StringWriter()
                model.write(stringWriter, "RDF/XML")
                stringWriter.toString()
            }
            "N-TRIPLES", "NT" -> {
                val stringWriter = java.io.StringWriter()
                model.write(stringWriter, "N-TRIPLES")
                stringWriter.toString()
            }
            "JSON-LD", "JSONLD" -> {
                val stringWriter = java.io.StringWriter()
                model.write(stringWriter, "JSON-LD")
                stringWriter.toString()
            }
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
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
}

/**
 * Extension functions for convenient Jena-Kastor interoperability.
 */

/**
 * Converts a Jena Model to a Kastor RdfGraph.
 */
fun Model.toKastorGraph(): RdfGraph = JenaBridge.fromJenaModel(this)

/**
 * Converts a Jena Graph to a Kastor RdfGraph.
 */
fun Graph.toKastorGraph(): RdfGraph = JenaBridge.fromJenaGraph(this)

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
 * Serializes a Kastor RdfGraph to a string.
 */
fun RdfGraph.serialize(format: String = "TURTLE"): String = JenaBridge.toString(this, format)
