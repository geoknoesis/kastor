package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Literal as JenaLiteral

/**
 * Internal utility for converting between Kastor RDF terms and Jena types.
 * This is an implementation detail and should not be used directly.
 */
internal object JenaTerms {
    /**
     * Converts a Kastor RDF term to a Jena RDFNode.
     */
    fun toNode(term: RdfTerm?): RDFNode? {
        val model = ModelFactory.createDefaultModel()
        return toNode(model, term)
    }

    /**
     * Converts a Kastor RDF term to a Jena RDFNode using a target model.
     */
    fun toNode(model: Model, term: RdfTerm?): RDFNode? {
        return when (term) {
            is Iri -> model.createResource(term.value)
            is BlankNode -> {
                val anonId = org.apache.jena.rdf.model.AnonId.create(term.id.removePrefix("_:"))
                model.createResource(anonId)
            }
            is TripleTerm -> {
                val subj = toNode(model, term.triple.subject) as Resource
                val pred = toNode(model, term.triple.predicate) as Property
                val obj = toNode(model, term.triple.obj) as RDFNode
                val triple = Triple.create(subj.asNode(), pred.asNode(), obj.asNode())
                @Suppress("DEPRECATION")
                val tripleNode = NodeFactory.createTripleNode(triple)
                model.asRDFNode(tripleNode)
            }
            is Literal -> {
                when (term) {
                    is LangString -> model.createLiteral(term.lexical, term.lang)
                    is TypedLiteral -> {
                        val datatype = org.apache.jena.datatypes.TypeMapper.getInstance()
                            .getSafeTypeByName(term.datatype.value)
                        model.createTypedLiteral(term.lexical, datatype)
                    }
                    else -> model.createLiteral(term.lexical)
                }
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
        val model = ModelFactory.createDefaultModel()
        return toResource(model, term)
    }

    fun toResource(model: Model, term: RdfResource): Resource {
        return when (term) {
            is Iri -> model.createResource(term.value)
            is BlankNode -> {
                val anonId = org.apache.jena.rdf.model.AnonId.create(term.id.removePrefix("_:"))
                model.createResource(anonId)
            }
            is TripleTerm -> {
                val subj = toNode(model, term.triple.subject) as Resource
                val pred = toNode(model, term.triple.predicate) as Property
                val obj = toNode(model, term.triple.obj) as RDFNode
                val triple = Triple.create(subj.asNode(), pred.asNode(), obj.asNode())
                @Suppress("DEPRECATION")
                val tripleNode = NodeFactory.createTripleNode(triple)
                model.asRDFNode(tripleNode) as Resource
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

    fun toProperty(model: Model, iri: Iri): Property {
        return model.createProperty(iri.value)
    }
    
    /**
     * Converts a Kastor RDF term to a Jena Value.
     * This is the most general conversion method.
     */
    fun toValue(term: RdfTerm): org.apache.jena.rdf.model.RDFNode {
        return toNode(term) ?: throw IllegalArgumentException("Cannot convert null term to Jena value")
    }
    
    /**
     * Converts a Jena Resource to a Kastor RDF resource.
     */
    fun fromResource(resource: Resource): RdfResource {
        return when {
            resource.isURIResource -> Iri(resource.uri)
            else -> BlankNode(resource.id.toString())
        }
    }
    
    /**
     * Converts a Jena Property to a Kastor IRI.
     */
    fun fromProperty(property: Property): Iri {
        return Iri(property.uri)
    }
}









