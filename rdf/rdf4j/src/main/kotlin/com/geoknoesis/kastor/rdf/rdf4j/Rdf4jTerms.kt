package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.Literal as Rdf4jLiteral
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/**
 * Internal utility for converting between Kastor RDF terms and RDF4J types.
 * This is an implementation detail and should not be used directly.
 */
internal object Rdf4jTerms {
    private val valueFactory = SimpleValueFactory.getInstance()
    
    fun toRdf4jResource(term: RdfTerm): Resource {
        return when (term) {
            is Iri -> valueFactory.createIRI(term.value)
            is BlankNode -> valueFactory.createBNode(term.id)
            is TripleTerm -> valueFactory.createTriple(
                toRdf4jResource(term.triple.subject),
                toRdf4jIri(term.triple.predicate),
                toRdf4jValue(term.triple.obj)
            )
            else -> throw IllegalArgumentException("Cannot convert ${term.javaClass} to RDF4J Resource")
        }
    }
    
    fun toRdf4jIri(iri: Iri): IRI {
        return valueFactory.createIRI(iri.value)
    }
    
    fun toRdf4jValue(term: RdfTerm): Value {
        return when (term) {
            is Iri -> valueFactory.createIRI(term.value)
            is BlankNode -> valueFactory.createBNode(term.id)
            is com.geoknoesis.kastor.rdf.LangString -> {
                valueFactory.createLiteral(term.lexical, term.lang)
            }
            is Literal -> {
                valueFactory.createLiteral(term.lexical, valueFactory.createIRI(term.datatype.value))
            }
            is TripleTerm -> valueFactory.createTriple(
                toRdf4jResource(term.triple.subject),
                toRdf4jIri(term.triple.predicate),
                toRdf4jValue(term.triple.obj)
            )
            else -> throw IllegalArgumentException("Cannot convert ${term.javaClass} to RDF4J Value")
        }
    }
    
    fun fromRdf4jResource(resource: Resource): RdfResource {
        return when (resource) {
            is IRI -> Iri(resource.stringValue())
            is BNode -> BlankNode(resource.id)
            is Triple -> TripleTerm(RdfTriple(
                fromRdf4jResource(resource.subject),
                fromRdf4jIri(resource.predicate),
                fromRdf4jValue(resource.`object`)
            ))
            else -> throw IllegalArgumentException("Unknown RDF4J Resource type: ${resource.javaClass}")
        }
    }
    
    fun fromRdf4jIri(iri: IRI): Iri {
        return Iri(iri.stringValue())
    }
    
    fun fromRdf4jValue(value: Value): RdfTerm {
        return when (value) {
            is IRI -> Iri(value.stringValue())
            is BNode -> BlankNode(value.id)
            is Rdf4jLiteral -> {
                val lang = value.language.orElse(null)
                val datatype = value.datatype?.let { Iri(it.stringValue()) }
                when {
                    lang != null -> com.geoknoesis.kastor.rdf.LangString(value.stringValue(), lang)
                    datatype != null -> Literal(value.stringValue(), datatype)
                    else -> Literal(value.stringValue())
                }
            }
            is Triple -> TripleTerm(RdfTriple(
                fromRdf4jResource(value.subject),
                fromRdf4jIri(value.predicate),
                fromRdf4jValue(value.`object`)
            ))
            else -> throw IllegalArgumentException("Unknown RDF4J Value type: ${value.javaClass}")
        }
    }
}









