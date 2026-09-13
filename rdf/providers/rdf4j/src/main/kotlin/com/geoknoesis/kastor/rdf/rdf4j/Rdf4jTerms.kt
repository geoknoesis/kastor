package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.Literal as Rdf4jLiteral

/**
 * Internal utility for converting between Kastor RDF terms and RDF4J 5.3 types.
 *
 * This is an implementation detail and should not be used directly.
 *
 * - **Lexical forms are preserved exactly**: every typed literal converts to a [TypedLiteral] with the
 *   stored lexical form (only the exact spellings `"true"`/`"false"` of `xsd:boolean` map to
 *   [TrueLiteral]/[FalseLiteral]). Nothing is canonicalised and ill-typed values never throw.
 * - **Triple terms** use RDF4J's [Triple] value (`ValueFactory.createTriple`); they are object-only in RDF 1.2.
 * - **Base direction**: RDF4J 5.3 has no base-direction field on literals. Its Rio parsers read the
 *   RDF 1.2 form `"x"@ar--rtl` as the language tag `ar--rtl` and its writers emit it back unchanged, so
 *   that combined tag is RDF4J's actual representation. [LangString]s with a direction are stored that
 *   way and decoded back into `lang` + [Direction]. Consequence inside RDF4J SPARQL: `LANG(?o)` returns
 *   `ar--rtl`. (`--` cannot occur in a BCP 47 tag, so the encoding is unambiguous.)
 */
internal object Rdf4jTerms {
    private val valueFactory = SimpleValueFactory.getInstance()
    // RDF 1.2 base directions are lowercase only ("en--LTR" is not a directional tag).
    private val directionSuffix = Regex("^(.+)--(ltr|rtl)$")
    private val languageTag = Regex("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$")
    private const val RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
    private const val RDF_DIR_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString"

    /**
     * Checks literal well-formedness that Rio tolerates: a well-formed BCP 47 language tag, and no
     * `rdf:langString` / `rdf:dirLangString` literal without a language tag.
     * @throws IllegalArgumentException when the term is not valid RDF.
     */
    fun requireWellFormed(term: RdfTerm) {
        when (term) {
            is LangString -> require(languageTag.matches(term.lang)) { "Invalid language tag: '${term.lang}'" }
            is Literal -> require(term.datatype.value != RDF_LANG_STRING && term.datatype.value != RDF_DIR_LANG_STRING) {
                "Literal typed ${term.datatype.value} requires a language tag"
            }
            is TripleTerm -> requireWellFormed(term.triple.obj)
            else -> Unit
        }
    }

    fun toRdf4jResource(term: RdfTerm): Resource {
        return when (term) {
            is Iri -> valueFactory.createIRI(term.value)
            is BlankNode -> valueFactory.createBNode(term.id)
            else -> throw IllegalArgumentException(
                "Cannot convert ${term.javaClass} to RDF4J Resource. " +
                    "Triple terms are object-only in RDF 1.2.",
            )
        }
    }

    fun toRdf4jIri(iri: Iri): IRI {
        return valueFactory.createIRI(iri.value)
    }

    fun toRdf4jValue(term: RdfTerm): Value {
        return when (term) {
            is Iri -> valueFactory.createIRI(term.value)
            is BlankNode -> valueFactory.createBNode(term.id)
            is LangString -> {
                val direction = term.direction
                val tag = if (direction == null) term.lang else "${term.lang}--${direction.token}"
                valueFactory.createLiteral(term.lexical, tag)
            }
            is Literal -> valueFactory.createLiteral(term.lexical, valueFactory.createIRI(term.datatype.value))
            is TripleTerm -> valueFactory.createTriple(
                toRdf4jResource(term.triple.subject),
                toRdf4jIri(term.triple.predicate),
                toRdf4jValue(term.triple.obj),
            )
            else -> throw IllegalArgumentException("Cannot convert ${term.javaClass} to RDF4J Value")
        }
    }

    fun fromRdf4jResource(resource: Resource): RdfResource {
        return when (resource) {
            is IRI -> Iri(resource.stringValue())
            is BNode -> BlankNode(resource.id)
            else -> throw IllegalArgumentException(
                "Unsupported RDF4J Resource type for RDF 1.2 (subjects must be IRI or BNode): " +
                    resource.javaClass,
            )
        }
    }

    fun fromRdf4jIri(iri: IRI): Iri {
        return Iri(iri.stringValue())
    }

    fun fromRdf4jValue(value: Value): RdfTerm {
        return when (value) {
            is IRI -> Iri(value.stringValue())
            is BNode -> BlankNode(value.id)
            is Rdf4jLiteral -> fromRdf4jLiteral(value)
            is Triple -> TripleTerm(
                RdfTriple(
                    fromRdf4jResource(value.subject),
                    fromRdf4jIri(value.predicate),
                    fromRdf4jValue(value.`object`),
                )
            )
            else -> throw IllegalArgumentException("Unknown RDF4J Value type: ${value.javaClass}")
        }
    }

    private fun fromRdf4jLiteral(value: Rdf4jLiteral): Literal {
        val lexical = value.label
        val lang = value.language.orElse(null)
        if (lang != null) {
            val match = directionSuffix.matchEntire(lang)
            return if (match != null) {
                LangString(lexical, match.groupValues[1], Direction.fromToken(match.groupValues[2]))
            } else {
                LangString(lexical, lang)
            }
        }
        val datatype = value.datatype?.let { Iri(it.stringValue()) } ?: XSD.string
        return when {
            datatype == XSD.boolean && lexical == "true" -> TrueLiteral
            datatype == XSD.boolean && lexical == "false" -> FalseLiteral
            else -> TypedLiteral(lexical, datatype)
        }
    }
}
