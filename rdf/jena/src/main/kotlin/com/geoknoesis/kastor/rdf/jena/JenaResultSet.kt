package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.query.ResultSet as JenaResultSet
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Literal
import org.apache.jena.rdf.model.Resource

class JenaResultSet(private val jenaResultSet: JenaResultSet) : QueryResult {
    override fun iterator(): Iterator<BindingSet> {
        return object : Iterator<BindingSet> {
            private val jenaIterator = jenaResultSet.iterator()
            
            override fun hasNext(): Boolean = jenaIterator.hasNext()
            
            override fun next(): BindingSet {
                val jenaBinding = jenaIterator.next()
                return object : BindingSet {
                    override fun get(name: String): RdfTerm? {
                        val jenaNode = jenaBinding.get(name)
                        return jenaNode?.toKastorTerm()
                    }
                    
                    override fun getVariableNames(): Set<String> {
                        return jenaBinding.varNames().asSequence().toSet()
                    }
                    
                    override fun hasBinding(variable: String): Boolean {
                        return jenaBinding.get(variable) != null
                    }
                }
            }
        }
    }
    
    override fun toList(): List<BindingSet> {
        return iterator().asSequence().toList()
    }
    
    override fun first(): BindingSet? {
        return if (jenaResultSet.hasNext()) {
            val jenaBinding = jenaResultSet.next()
            object : BindingSet {
                override fun get(name: String): RdfTerm? {
                    val jenaNode = jenaBinding.get(name)
                    return jenaNode?.toKastorTerm()
                }
                
                override fun getVariableNames(): Set<String> {
                    return jenaBinding.varNames().asSequence().toSet()
                }
                
                override fun hasBinding(variable: String): Boolean {
                    return jenaBinding.get(variable) != null
                }
            }
        } else null
    }
    
    override fun asSequence(): Sequence<BindingSet> {
        return iterator().asSequence()
    }
    
    override fun count(): Int {
        return jenaResultSet.resultVars.size
    }
    
    
    private fun RDFNode.toKastorTerm(): RdfTerm {
        return when {
            this.isLiteral -> {
                val literal = this.asLiteral()
                when {
                    literal.language.isNotEmpty() -> Literal(literal.lexicalForm, literal.language)
                    literal.datatypeURI != null -> Literal(literal.lexicalForm, Iri(literal.datatypeURI))
                    else -> Literal(literal.lexicalForm)
                }
            }
            this.isURIResource -> {
                Iri(this.asResource().uri)
            }
            this.isAnon -> {
                BlankNode(this.asResource().id.toString())
            }
            else -> {
                throw IllegalArgumentException("Unsupported RDF node type: $this")
            }
        }
    }
}
