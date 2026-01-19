package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Literal as JenaLiteral
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Model

object JenaTerms {
    /**
     * Converts a Kastor RDF term to a Jena RDFNode.
     */
    fun toNode(term: RdfTerm?): RDFNode? {
        return when (term) {
            is Iri -> ModelFactory.createDefaultModel().createResource(term.value)
            is BlankNode -> ModelFactory.createDefaultModel().createResource(term.id)
            is TripleTerm -> {
                // RDF-star support: convert the embedded triple to a Jena statement
                val model = ModelFactory.createDefaultModel()
                val subj = toNode(term.triple.subject) as Resource
                val pred = toNode(term.triple.predicate) as Property
                val obj = toNode(term.triple.obj)
                val statement = model.createStatement(subj, pred, obj)
                model.createResource(statement)
            }
            is Literal -> {
                val model = ModelFactory.createDefaultModel()
                // Use a simpler approach without specific type checks
                model.createLiteral(term.lexical)
            }
            null -> null
            else -> throw IllegalArgumentException("Unsupported RDF term type: ${term.javaClass}")
        }
    }
    
    /**
     * Converts a Jena RDFNode to a Kastor RDF term.
     */
    fun fromNode(node: RDFNode): RdfTerm {
        return when (node) {
            is Resource -> {
                if (node.isURIResource) {
                    Iri(node.uri)
                } else {
                    BlankNode(node.id.toString())
                }
            }
            is Property -> Iri(node.uri)
            is JenaLiteral -> {
                when {
                    node.language.isNotEmpty() -> Literal(node.lexicalForm, node.language)
                    node.datatypeURI != null -> {
                        val datatype = Iri(node.datatypeURI)
                        when (datatype) {
                            XSD.string -> Literal(node.lexicalForm, XSD.string)
                            XSD.integer -> node.lexicalForm.toIntOrNull()?.toLiteral() ?: Literal(node.lexicalForm, XSD.string)
                            XSD.decimal -> node.lexicalForm.toBigDecimalOrNull()?.toLiteral() ?: Literal(node.lexicalForm, XSD.string)
                            XSD.double -> node.lexicalForm.toDoubleOrNull()?.toLiteral() ?: Literal(node.lexicalForm, XSD.string)
                            XSD.boolean -> when (node.lexicalForm.lowercase()) {
                                "true" -> true.toLiteral()
                                "false" -> false.toLiteral()
                                else -> Literal(node.lexicalForm, XSD.string)
                            }
                            XSD.date -> try {
                                java.time.LocalDate.parse(node.lexicalForm).toLiteral()
                            } catch (e: Exception) {
                                Literal(node.lexicalForm, XSD.string)
                            }
                            XSD.dateTime -> try {
                                java.time.LocalDateTime.parse(node.lexicalForm).toLiteral()
                            } catch (e: Exception) {
                                Literal(node.lexicalForm, XSD.string)
                            }
                            else -> Literal(node.lexicalForm, datatype)
                        }
                    }
                    else -> Literal(node.lexicalForm)
                }
            }
            else -> throw IllegalArgumentException("Unknown RDF node type: ${node.javaClass}")
        }
    }
    
    /**
     * Converts a Kastor RDF term to a Jena Resource.
     * Convenience method for subjects and predicates.
     */
    fun toResource(term: RdfResource): Resource {
        return when (term) {
            is Iri -> ModelFactory.createDefaultModel().createResource(term.value)
            is BlankNode -> ModelFactory.createDefaultModel().createResource(term.id)
            is TripleTerm -> {
                // RDF-star support: convert the embedded triple to a Jena statement
                val model = ModelFactory.createDefaultModel()
                val subj = toNode(term.triple.subject) as Resource
                val pred = toNode(term.triple.predicate) as Property
                val obj = toNode(term.triple.obj)
                val statement = model.createStatement(subj, pred, obj)
                model.createResource(statement) as Resource
            }
        }
    }
    
    /**
     * Converts a Kastor IRI to a Jena Property.
     * Convenience method for predicates.
     */
    fun toProperty(iri: Iri): Property {
        return ModelFactory.createDefaultModel().createProperty(iri.value)
    }
    
    /**
     * Converts a Kastor RDF term to a Jena Value.
     * This is the most general conversion method.
     */
    fun toValue(term: RdfTerm): org.apache.jena.rdf.model.RDFNode {
        return toNode(term) ?: throw IllegalArgumentException("Cannot convert null term to Jena value")
    }
}









