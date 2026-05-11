package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.Literal as Rdf4jLiteral
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/**
 * Internal utility for converting between Kastor RDF terms and RDF4J types.
 *
 * This is an implementation detail and should not be used directly. Targets the
 * RDF 1.2 data model: triple terms are object-only (so no [TripleTerm] branch in
 * [toRdf4jResource]), and language literals carry an optional base direction.
 *
 * On RDF4J builds that pre-date the spec rename (`createTripleTerm` / 5.2+)
 * we fall back to `createTriple` so the module keeps building against the
 * pinned RDF4J 5.1.x line.
 */
internal object Rdf4jTerms {
    private val valueFactory = SimpleValueFactory.getInstance()

    private val createTripleTermMethod: java.lang.reflect.Method? = runCatching {
        ValueFactory::class.java.getMethod(
            "createTripleTerm",
            Resource::class.java,
            IRI::class.java,
            Value::class.java,
        )
    }.getOrNull()

    private val createDirLangLiteralMethod: java.lang.reflect.Method? = runCatching {
        // RDF4J 5.x: createLiteral(String, String, String) where 3rd arg is the
        // direction token (ltr/rtl). The exact API may move; we look it up by
        // reflection so we compile against either.
        ValueFactory::class.java.getMethod(
            "createLiteral",
            String::class.java,
            String::class.java,
            String::class.java,
        )
    }.getOrNull()

    private val getLiteralBaseDirection: java.lang.reflect.Method? = runCatching {
        Rdf4jLiteral::class.java.getMethod("getBaseDirection")
    }.getOrNull()

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
            is com.geoknoesis.kastor.rdf.LangString -> {
                val direction = term.direction
                val dirLang = createDirLangLiteralMethod
                if (direction != null && dirLang != null) {
                    runCatching {
                        dirLang.invoke(
                            valueFactory, term.lexical, term.lang, direction.token
                        ) as Value
                    }.getOrElse {
                        // The 3-arg overload exists but may not accept a direction
                        // token on older RDF4J builds. Drop the direction.
                        valueFactory.createLiteral(term.lexical, term.lang)
                    }
                } else {
                    valueFactory.createLiteral(term.lexical, term.lang)
                }
            }
            is Literal -> {
                valueFactory.createLiteral(term.lexical, valueFactory.createIRI(term.datatype.value))
            }
            is TripleTerm -> createTripleTermValue(
                toRdf4jResource(term.triple.subject),
                toRdf4jIri(term.triple.predicate),
                toRdf4jValue(term.triple.obj)
            )
            else -> throw IllegalArgumentException("Cannot convert ${term.javaClass} to RDF4J Value")
        }
    }

    /**
     * Creates an RDF 1.2 triple-term value, falling back to the legacy
     * `createTriple(...)` on older RDF4J versions.
     */
    private fun createTripleTermValue(s: Resource, p: IRI, o: Value): Value {
        createTripleTermMethod?.let { method ->
            return runCatching { method.invoke(valueFactory, s, p, o) as Value }.getOrElse {
                @Suppress("DEPRECATION")
                valueFactory.createTriple(s, p, o)
            }
        }
        @Suppress("DEPRECATION")
        return valueFactory.createTriple(s, p, o)
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
            is Rdf4jLiteral -> {
                val lang = value.language.orElse(null)
                val datatype = value.datatype?.let { Iri(it.stringValue()) }
                when {
                    lang != null -> {
                        val direction = readDirection(value)
                        com.geoknoesis.kastor.rdf.LangString(value.stringValue(), lang, direction)
                    }
                    datatype != null -> Literal(value.stringValue(), datatype)
                    else -> Literal(value.stringValue())
                }
            }
            is Triple -> TripleTerm(
                RdfTriple(
                    fromRdf4jResource(value.subject),
                    fromRdf4jIri(value.predicate),
                    fromRdf4jValue(value.`object`)
                )
            )
            else -> throw IllegalArgumentException("Unknown RDF4J Value type: ${value.javaClass}")
        }
    }

    private fun readDirection(literal: Rdf4jLiteral): Direction? {
        val method = getLiteralBaseDirection ?: return null
        val raw = runCatching { method.invoke(literal) }.getOrNull() ?: return null
        // RDF4J returns either an Optional, an enum, or a String depending on
        // version. Normalise via toString and let Direction.fromToken parse.
        val token = when (raw) {
            is java.util.Optional<*> -> raw.orElse(null)?.toString()
            else -> raw.toString()
        } ?: return null
        return Direction.fromToken(token)
    }
}









