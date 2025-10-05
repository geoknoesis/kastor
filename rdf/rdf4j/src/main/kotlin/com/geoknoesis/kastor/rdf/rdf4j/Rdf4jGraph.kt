package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.RepositoryResult

class Rdf4jGraph(
    private val connection: RepositoryConnection,
    private val context: org.eclipse.rdf4j.model.Resource? = null
) : RdfGraph {
    
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
        val result: RepositoryResult<Statement> = connection.getStatements(null, null, null, false, context)
        while (result.hasNext()) {
            val statement = result.next()
            val subject = Rdf4jTerms.fromRdf4jResource(statement.subject)
            val predicate = Rdf4jTerms.fromRdf4jIri(statement.predicate)
            val obj = Rdf4jTerms.fromRdf4jValue(statement.`object`)
            triples.add(RdfTriple(subject, predicate, obj))
        }
        return triples
    }
    
    override fun clear(): Boolean {
        val wasEmpty = connection.isEmpty
        connection.clear()
        return !wasEmpty
    }
    
    override fun size(): Int = connection.size().toInt()
}
