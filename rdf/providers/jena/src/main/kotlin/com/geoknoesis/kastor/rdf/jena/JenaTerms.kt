package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.TextDirection
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
 * Targets the RDF 1.2 data model using the Jena 6 APIs directly: triple terms via
 * [NodeFactory.createTripleTerm] / [Node.isTripleTerm], and directional language
 * strings via [NodeFactory.createLiteralDirLang] / [Node.getLiteralBaseDirection].
 *
 * **Lexical forms are preserved exactly in both directions.** A literal such as
 * `"1.50"^^xsd:decimal` or `"007"^^xsd:integer` converts to a [TypedLiteral] with the
 * same lexical form, never a canonicalised value; otherwise the converted term would
 * no longer identify the stored RDF term (`removeTriple`/`hasTriple` would miss it).
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
            null -> null
            else -> model.asRDFNode(toJenaNode(term))
        }
    }

    /** Converts a Kastor term to a raw Jena [Node]. */
    fun toJenaNode(term: RdfTerm): Node = when (term) {
        is Iri -> NodeFactory.createURI(term.value)
        is BlankNode -> NodeFactory.createBlankNode(term.id.removePrefix("_:"))
        is TripleTerm -> NodeFactory.createTripleTerm(
            toJenaNode(term.triple.subject),
            NodeFactory.createURI(term.triple.predicate.value),
            toJenaNode(term.triple.obj),
        )
        is LangString -> {
            val direction = term.direction
            if (direction != null) {
                NodeFactory.createLiteralDirLang(term.lexical, term.lang, TextDirection.create(direction.token))
            } else {
                NodeFactory.createLiteralLang(term.lexical, term.lang)
            }
        }
        // TypedLiteral, TrueLiteral and FalseLiteral: keep the exact lexical form and datatype.
        is Literal -> NodeFactory.createLiteralDT(
            term.lexical,
            TypeMapper.getInstance().getSafeTypeByName(term.datatype.value),
        )
        else -> throw IllegalArgumentException("Unsupported RDF term type: ${term.javaClass}")
    }

    /**
     * Converts a Jena RDFNode to a Kastor RDF term.
     */
    fun fromNode(node: RDFNode): RdfTerm = fromJenaNode(node.asNode())

    /** Converts a raw Jena [Node] to a Kastor term, preserving literal lexical forms. */
    fun fromJenaNode(node: Node): RdfTerm = when {
        node.isURI -> Iri(node.uri)
        node.isBlank -> BlankNode(node.blankNodeLabel)
        node.isTripleTerm -> {
            val t: Triple = node.triple
            val subject = fromJenaNode(t.subject) as? RdfResource
                ?: throw IllegalArgumentException("RDF 1.2 triple-term subjects must be IRIs or blank nodes: $t")
            TripleTerm(RdfTriple(subject, Iri(t.predicate.uri), fromJenaNode(t.`object`)))
        }
        node.isLiteral -> literalFromJena(node)
        else -> throw IllegalArgumentException("Unknown RDF node type: $node")
    }

    private fun literalFromJena(node: Node): Literal {
        val lexical = node.literalLexicalForm
        val language = node.literalLanguage
        if (!language.isNullOrEmpty()) {
            return LangString(lexical, language, node.literalBaseDirection?.let { Direction.fromToken(it.direction()) })
        }
        val datatype = node.literalDatatypeURI?.let(::Iri) ?: XSD.string
        return when {
            // Only the exact canonical boolean spellings map to the singletons; "1"/"0"
            // stay TypedLiterals so their lexical form is preserved.
            datatype == XSD.boolean && lexical == "true" -> TrueLiteral
            datatype == XSD.boolean && lexical == "false" -> FalseLiteral
            else -> TypedLiteral(lexical, datatype)
        }
    }

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
    fun toValue(term: RdfTerm): RDFNode {
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

    /** Converts a Jena graph [Triple] to a Kastor triple. */
    fun fromJenaTriple(triple: Triple): RdfTriple {
        val subject = fromJenaNode(triple.subject) as? RdfResource
            ?: throw IllegalArgumentException("RDF 1.2 subjects must be IRIs or blank nodes: $triple")
        return RdfTriple(subject, Iri(triple.predicate.uri), fromJenaNode(triple.`object`))
    }

    /** Converts a Kastor triple to a Jena graph [Triple]. */
    fun toJenaTriple(triple: RdfTriple): Triple =
        Triple.create(toJenaNode(triple.subject), NodeFactory.createURI(triple.predicate.value), toJenaNode(triple.obj))
}
