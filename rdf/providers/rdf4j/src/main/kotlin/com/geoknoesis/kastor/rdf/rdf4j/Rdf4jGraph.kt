package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.RepositoryResult

/**
 * Internal RDF4J adapter for RdfGraph.
 * This is an implementation detail and should not be used directly.
 * Use [RdfGraph] interface instead.
 */
internal class Rdf4jGraph(
    private val connection: RepositoryConnection,
    private val context: org.eclipse.rdf4j.model.Resource? = null
) : MutableRdfGraph {
    
    override fun addTriple(triple: RdfTriple) {
        val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
        val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
        val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
        connection.add(subject, predicate, obj, context)
    }
    
    override fun addTriples(triples: Collection<RdfTriple>) {
        triples.forEach { addTriple(it) }
    }
    
    override fun removeTriple(triple: RdfTriple): Boolean {
        val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
        val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
        val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
        connection.remove(subject, predicate, obj, context)
        return true
    }
    
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        var anyRemoved = false
        triples.forEach { triple ->
            if (removeTriple(triple)) {
                anyRemoved = true
            }
        }
        return anyRemoved
    }
    
    override fun hasTriple(triple: RdfTriple): Boolean {
        val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
        val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
        val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
        return connection.hasStatement(subject, predicate, obj, false, context)
    }
    
    override fun getTriples(): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        // RepositoryResult holds a native cursor that must be closed; `use` guarantees
        // release even if term conversion throws part-way through iteration.
        connection.getStatements(null, null, null, false, context).use { result ->
            while (result.hasNext()) {
                val statement = result.next()
                val subject = Rdf4jTerms.fromRdf4jResource(statement.subject)
                val predicate = Rdf4jTerms.fromRdf4jIri(statement.predicate)
                val obj = Rdf4jTerms.fromRdf4jValue(statement.`object`)
                triples.add(RdfTriple(subject, predicate, obj))
            }
        }
        return triples
    }

    override fun clear(): Boolean {
        // Only clear this graph's context. `connection.clear()` with no arguments
        // wipes every context in the repository (silent cross-graph data loss).
        val wasEmpty = if (context != null) {
            !connection.hasStatement(null, null, null, false, context)
        } else {
            connection.isEmpty
        }
        if (context != null) connection.clear(context) else connection.clear()
        return !wasEmpty
    }

    // `connection.size()` with no context counts the whole repository; scope to this graph.
    override fun size(): Int =
        if (context != null) connection.size(context).toInt() else connection.size().toInt()
}









