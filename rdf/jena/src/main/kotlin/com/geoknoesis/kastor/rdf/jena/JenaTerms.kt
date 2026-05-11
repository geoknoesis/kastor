package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.graph.Node
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
 *
 * Targets the RDF 1.2 data model: triple terms via
 * [NodeFactory.createTripleTerm] (Jena 5.4+) and directional language strings
 * via [createLiteralDirLang] (Jena 5.4+, accessed reflectively to keep the
 * code building against any 5.x release that supplies the method).
 */
internal object JenaTerms {

    /**
     * Reflective handle to `NodeFactory.createLiteralDirLang(String, String, String)`,
     * the Jena 5 API for RDF 1.2 directional language strings. Resolved once at
     * class load; null if the running Jena does not expose it (very old 5.x).
     */
    private val createLiteralDirLang: java.lang.reflect.Method? = runCatching {
        NodeFactory::class.java.getMethod(
            "createLiteralDirLang",
            String::class.java,
            String::class.java,
            String::class.java,
        )
    }.getOrNull()

    /**
     * Reflective handle to `Node.getLiteralBaseDirection()`. Returns either a
     * `TextDirection` enum or null/string depending on Jena version. We only
     * use its `toString()` value so we can normalise to ltr/rtl.
     */
    private val getLiteralBaseDirection: java.lang.reflect.Method? = runCatching {
        Node::class.java.getMethod("getLiteralBaseDirection")
    }.getOrNull()

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
                val subj = toResource(model, term.triple.subject)
                val pred = toProperty(model, term.triple.predicate)
                val obj = toNode(model, term.triple.obj) ?: error("triple-term object cannot be null")
                val triple = Triple.create(subj.asNode(), pred.asNode(), obj.asNode())
                val tripleNode = createTripleTermNode(triple)
                model.asRDFNode(tripleNode)
            }
            is Literal -> {
                when (term) {
                    is LangString -> {
                        val direction = term.direction
                        if (direction != null && createLiteralDirLang != null) {
                            val node = createLiteralDirLang.invoke(
                                null, term.lexical, term.lang, direction.token
                            ) as Node
                            model.asRDFNode(node)
                        } else {
                            // Fall back to a plain language-tagged literal when the
                            // running Jena does not implement the directional API.
                            model.createLiteral(term.lexical, term.lang)
                        }
                    }
                    is TypedLiteral -> {
                        val datatype = org.apache.jena.datatypes.TypeMapper.getInstance()
                            .getSafeTypeByName(term.datatype.value)
                        model.createTypedLiteral(term.lexical, datatype)
                    }
                    // TrueLiteral / FalseLiteral and any other Literal subtype with a
                    // declared datatype must round-trip as a typed literal so the
                    // datatype is preserved in the underlying Jena Model.
                    else -> {
                        val datatype = org.apache.jena.datatypes.TypeMapper.getInstance()
                            .getSafeTypeByName(term.datatype.value)
                        model.createTypedLiteral(term.lexical, datatype)
                    }
                }
            }
            null -> null
            else -> throw IllegalArgumentException("Unsupported RDF term type: ${term.javaClass}")
        }
    }

    /**
     * Builds a Jena RDF 1.2 triple-term node, falling back to the legacy
     * `createTripleNode` if the running Jena predates the spec rename.
     */
    private fun createTripleTermNode(triple: Triple): Node {
        val factory = NodeFactory::class.java
        return runCatching {
            factory.getMethod("createTripleTerm", Triple::class.java).invoke(null, triple) as Node
        }.recoverCatching {
            @Suppress("DEPRECATION")
            factory.getMethod("createTripleNode", Triple::class.java).invoke(null, triple) as Node
        }.getOrThrow()
    }

    /**
     * Converts a Jena RDFNode to a Kastor RDF term.
     */
    fun fromNode(node: RDFNode): RdfTerm {
        // Triple terms are reported by Jena 5 as their own RDFNode subtype (e.g.
        // StatementTermImpl) which does not extend Resource. Check that first.
        val rawNode = node.asNode()
        if (rawNode.isTripleTermSafe()) {
            val t = rawNode.getTripleTermSafe()
            return TripleTerm(
                RdfTriple(
                    fromNode(node.model.asRDFNode(t.subject)) as RdfResource,
                    (fromNode(node.model.asRDFNode(t.predicate)) as Iri),
                    fromNode(node.model.asRDFNode(t.`object`))
                )
            )
        }
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
                    node.language.isNotEmpty() -> {
                        val direction = readDirection(node.asNode())
                        if (direction != null) {
                            LangString(node.lexicalForm, node.language, direction)
                        } else {
                            Literal(node.lexicalForm, node.language)
                        }
                    }
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

    private fun readDirection(node: Node): Direction? {
        val method = getLiteralBaseDirection ?: return null
        val value = runCatching { method.invoke(node) }.getOrNull() ?: return null
        return Direction.fromToken(value.toString())
    }

    private fun Node.isTripleTermSafe(): Boolean = runCatching {
        // Jena 5.4+: Node.isTripleTerm(); fallback to the legacy isNodeTriple().
        Node::class.java.getMethod("isTripleTerm").invoke(this) as Boolean
    }.getOrElse {
        runCatching {
            Node::class.java.getMethod("isNodeTriple").invoke(this) as Boolean
        }.getOrDefault(false)
    }

    private fun Node.getTripleTermSafe(): Triple = runCatching {
        Node::class.java.getMethod("getTriple").invoke(this) as Triple
    }.getOrThrow()

    /**
     * Converts a Kastor RDF resource to a Jena Resource. RDF 1.2 forbids triple
     * terms in subject position, so this overload only sees Iri / BlankNode.
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









