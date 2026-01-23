package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
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
     * Convert RDF4J format string to RDF4J RDFFormat enum.
     */
    private fun toRdf4jFormat(format: String): RDFFormat {
        val normalized = format.uppercase().trim()
        return when (normalized) {
            "TURTLE", "TTL" -> RDFFormat.TURTLE
            "JSON-LD", "JSONLD" -> RDFFormat.JSONLD
            "RDF/XML", "RDFXML", "XML" -> RDFFormat.RDFXML
            "N-TRIPLES", "NT", "NTRIPLES" -> RDFFormat.NTRIPLES
            "TRIG", "TRI-G" -> RDFFormat.TRIG
            "N-QUADS", "NQUADS", "NQ" -> RDFFormat.NQUADS
            else -> throw RdfFormatException("Unsupported RDF format: $format")
        }
    }
    
    /**
     * Serialize a Kastor RdfGraph to a string using RDF4J.
     */
    fun serializeGraph(graph: RdfGraph, format: String): String {
        val rdf4jFormat = toRdf4jFormat(format)
        val model = LinkedHashModel()
        
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
    fun parseGraph(inputStream: InputStream, format: String): MutableRdfGraph {
        val rdf4jFormat = toRdf4jFormat(format)
        
        // Parse using RDF4J - parse directly to a Model
        val model: Model = try {
            Rio.parse(inputStream, "", rdf4jFormat)
        } catch (e: Exception) {
            throw RdfFormatException("Failed to parse RDF data: ${e.message}", e)
        }
        
        // Convert RDF4J model to Kastor graph
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
    fun serializeDataset(repository: RdfRepository, format: String): String {
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
            throw RdfFormatException("Failed to serialize dataset: ${e.message}", e)
        }
    }
    
    /**
     * Parse RDF dataset data from an input stream into a Kastor repository using RDF4J.
     */
    fun parseDataset(repository: RdfRepository, inputStream: InputStream, format: String) {
        val rdf4jFormat = toRdf4jFormat(format)
        val rdf4jRepo = repository as? Rdf4jRepository
            ?: throw UnsupportedOperationException("Rdf4jFormatSupport can only parse into RDF4J repositories")
        
        val connection = rdf4jRepo.getRdf4jConnection()
        
        try {
            // Parse using RDF4J - this handles both default graph and named graphs
            val parser = Rio.createParser(rdf4jFormat)
            parser.setRDFHandler(object : org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler() {
                override fun handleStatement(statement: org.eclipse.rdf4j.model.Statement) {
                    val context = statement.context
                    if (context != null) {
                        // Named graph
                        connection.add(statement.subject, statement.predicate, statement.`object`, context)
                    } else {
                        // Default graph
                        connection.add(statement.subject, statement.predicate, statement.`object`)
                    }
                }
            })
            parser.parse(inputStream, "")
        } catch (e: Exception) {
            throw RdfFormatException("Failed to parse RDF dataset: ${e.message}", e)
        }
    }
}

