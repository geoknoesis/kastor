package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.StmtIterator

/**
 * Internal Jena adapter for RdfGraph.
 * This is an implementation detail and should not be used directly.
 * Use [RdfGraph] interface instead.
 */
internal class JenaGraph(val model: Model) : MutableRdfGraph {
    
    override fun addTriple(triple: RdfTriple) {
        val subject = JenaTerms.toResource(triple.subject)
        val predicate = JenaTerms.toProperty(triple.predicate)
        val obj = JenaTerms.toNode(triple.obj)
        model.add(subject, predicate, obj)
    }
    
    override fun addTriples(triples: Collection<RdfTriple>) {
        triples.forEach { addTriple(it) }
    }
    
    override fun removeTriple(triple: RdfTriple): Boolean {
        val subject = JenaTerms.toResource(triple.subject)
        val predicate = JenaTerms.toProperty(triple.predicate)
        val obj = JenaTerms.toNode(triple.obj)
        val removed = model.remove(subject, predicate, obj)
        return removed != null
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
        val subject = JenaTerms.toResource(triple.subject)
        val predicate = JenaTerms.toProperty(triple.predicate)
        val obj = JenaTerms.toNode(triple.obj)
        return model.contains(subject, predicate, obj)
    }
    
    override fun getTriples(): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        val iterator: StmtIterator = model.listStatements()
        while (iterator.hasNext()) {
            val statement = iterator.nextStatement()
            val subject = JenaTerms.fromNode(statement.subject) as RdfResource
            val predicate = JenaTerms.fromNode(statement.predicate) as Iri
            val obj = JenaTerms.fromNode(statement.`object`)
            triples.add(RdfTriple(subject, predicate, obj))
        }
        return triples
    }
    
    override fun clear(): Boolean {
        val wasEmpty = model.isEmpty
        model.removeAll()
        return !wasEmpty
    }
    
    override fun size(): Int = model.size().toInt()
}









