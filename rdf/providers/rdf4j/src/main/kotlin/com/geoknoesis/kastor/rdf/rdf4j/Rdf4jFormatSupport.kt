package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import java.io.InputStream
import java.io.StringWriter

/**
 * RDF4J format support utilities for serialization and parsing.
 * 
 * This is an internal implementation detail and should not be used directly.
 * Use the provider-agnostic API via [RdfProvider.serializeGraph] and [RdfProvider.parseGraph].
 */
internal object Rdf4jFormatSupport {
    
    /**
     * Convert a Kastor format string to an RDF4J [RDFFormat]. Accepts both the
     * RDF 1.1 and RDF 1.2 alias spellings (`TURTLE`, `TURTLE-1.2`, ...). All
     * RDF 1.2 aliases route to the same RDF4J writer factory because RDF4J 5.x
     * produces RDF 1.2 syntax by default when it sees triple terms or
     * directional language literals.
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
            else -> throw RdfFormatException.UnsupportedFormat(
                format,
                listOf(
                    "TURTLE", "TTL", "TURTLE-1.2", "TURTLESTAR",
                    "JSON-LD", "JSONLD", "JSON-LD-1.2",
                    "RDF/XML", "RDFXML", "XML",
                    "N-TRIPLES", "NT", "NTRIPLES", "N-TRIPLES-1.2",
                    "TRIG", "TRI-G", "TRIG-1.2", "TRIGSTAR",
                    "N-QUADS", "NQUADS", "NQ", "N-QUADS-1.2",
                )
            )
        }
    }
    
    /**
     * Serialize a Kastor RdfGraph to a string using RDF4J.
     */
    fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val rdf4jFormat = toRdf4jFormat(format)
        val model = LinkedHashModel()
        val valueFactory = SimpleValueFactory.getInstance()
        
        // Apply base URI and prefix mappings
        options.baseUri?.let { model.setNamespace("", it) }
        options.prefixMappings.forEach { (prefix, uri) ->
            model.setNamespace(prefix, uri)
        }
        
        // Convert Kastor triples to RDF4J statements
        graph.getTriples().forEach { triple ->
            val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
            val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
            val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
            model.add(subject, predicate, obj)
        }
        
        // Serialize to string
        val writer = StringWriter()
        Rio.write(model, writer, rdf4jFormat)
        return writer.toString()
    }
    
    /**
     * Parse RDF data from an input stream into a Kastor graph using RDF4J.
     */
    fun parseGraph(inputStream: InputStream, format: String): MutableRdfGraph =
        parseGraph(inputStream, format, "")

    /**
     * Parse RDF data into a graph using an explicit base IRI.
     *
     * @param baseIri base used to resolve relative IRIs in the source. Pass an
     *   empty string to use RDF4J's default behaviour.
     */
    fun parseGraph(inputStream: InputStream, format: String, baseIri: String): MutableRdfGraph {
        val rdf4jFormat = toRdf4jFormat(format)
        val model: Model = try {
            Rio.parse(inputStream, baseIri, rdf4jFormat)
        } catch (e: Exception) {
            throw RdfFormatException.Generic("Failed to parse RDF data: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
        }
        val triples = mutableListOf<RdfTriple>()
        model.forEach { statement ->
            val subject = Rdf4jTerms.fromRdf4jResource(statement.subject)
            val predicate = Rdf4jTerms.fromRdf4jIri(statement.predicate)
            val obj = Rdf4jTerms.fromRdf4jValue(statement.`object`)
            triples.add(RdfTriple(subject, predicate, obj))
        }
        return com.geoknoesis.kastor.rdf.provider.MemoryGraph(triples)
    }
    
    /**
     * Serialize a Kastor RdfRepository (dataset) to a string using RDF4J.
     */
    fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions = SerializationOptions.DEFAULT): String {
        val rdf4jFormat = toRdf4jFormat(format)
        val rdf4jRepo = repository as? Rdf4jRepository
            ?: throw UnsupportedOperationException("Rdf4jFormatSupport can only serialize RDF4J repositories")
        
        val connection = rdf4jRepo.getRdf4jConnection()
        val writer = StringWriter()
        
        try {
            // Collect all statements from the connection
            val statements = mutableListOf<org.eclipse.rdf4j.model.Statement>()
            connection.getStatements(null, null, null, false).use { stmts ->
                stmts.forEach { statements.add(it) }
            }
            connection.getContextIDs().use { contexts ->
                contexts.forEach { context ->
                    if (context != null) {
                        connection.getStatements(null, null, null, false, context).use { stmts ->
                            stmts.forEach { statements.add(it) }
                        }
                    }
                }
            }
            // Write statements using Rio
            Rio.write(statements, writer, rdf4jFormat)
            return writer.toString()
        } catch (e: Exception) {
            throw RdfFormatException.Generic("Failed to serialize dataset: ${e.message}", RdfErrorCode.FORMAT_SERIALIZATION_ERROR, e)
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

        val connection = rdf4jRepo.getRdf4jConnection()

        try {
            val parser = Rio.createParser(rdf4jFormat)
            parser.setRDFHandler(object : org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler() {
                override fun handleStatement(statement: org.eclipse.rdf4j.model.Statement) {
                    val context = statement.context
                    if (context != null) {
                        connection.add(statement.subject, statement.predicate, statement.`object`, context)
                    } else {
                        connection.add(statement.subject, statement.predicate, statement.`object`)
                    }
                }
            })
            parser.parse(inputStream, baseIri)
        } catch (e: Exception) {
            throw RdfFormatException.Generic("Failed to parse RDF dataset: ${e.message}", RdfErrorCode.FORMAT_PARSE_ERROR, e)
        }
    }
}

