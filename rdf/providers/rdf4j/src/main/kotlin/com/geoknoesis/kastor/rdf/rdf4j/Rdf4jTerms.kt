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

/** Where RDF-star quoted-triple subjects may occur in a store, ordered from cheapest to most expensive to look up. */
internal enum class QuotedLevel { NONE, FLAT, NESTED, UNKNOWN }

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

    /**
     * Checks literal well-formedness that Rio tolerates: a well-formed BCP 47 language tag, and no
     * `rdf:langString` / `rdf:dirLangString` literal without a language tag.
     * @throws IllegalArgumentException when the term is not valid RDF.
     */
    fun requireWellFormed(term: RdfTerm) = LiteralValidation.requireWellFormed(term)

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

    /**
     * Converts an RDF4J subject. An RDF-star quoted triple in subject position (which RDF 1.2 cannot
     * represent) becomes its deterministic reifier blank node, see [reifierFor].
     */
    fun fromRdf4jResource(resource: Resource): RdfResource {
        return when (resource) {
            is IRI -> Iri(resource.stringValue())
            is BNode -> BlankNode(resource.id)
            is Triple -> reifierFor(resource)
            else -> throw IllegalArgumentException(
                "Unsupported RDF4J Resource type for RDF 1.2 (subjects must be IRI or BNode): " +
                    resource.javaClass,
            )
        }
    }

    /** Blank-node id prefix of the deterministic reifiers standing in for RDF-star quoted-triple subjects. */
    const val STAR_REIFIER_PREFIX: String = "kastor-star-"

    /**
     * Deterministic, **reversible** reifier for an RDF-star quoted triple: the same triple always maps to the same
     * blank node, and the id encodes the triple itself, so lookups resolve a reifier back to its quoted triple
     * without scanning the store (see [quotedTripleOf]).
     *
     * Reserved scheme: `kastor-star-<base64url(encoded triple)>-<8 hex digits of its SHA-256>`. An id is only
     * treated as a reifier when it decodes to a well-formed triple **and** the checksum matches, so ordinary blank
     * nodes whose ids merely start with `kastor-star-` are never mistaken for reifiers.
     */
    fun reifierFor(triple: Triple): BlankNode {
        val encoded = StringBuilder().also { encodeValue(triple, it) }.toString().toByteArray(Charsets.UTF_8)
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
        return BlankNode("$STAR_REIFIER_PREFIX$payload-${checksum(encoded)}")
    }

    /** The quoted triple a reifier id produced by [reifierFor] stands for, or null when [id] is not such a reifier. */
    fun quotedTripleOf(id: String): Triple? {
        if (!id.startsWith(STAR_REIFIER_PREFIX)) return null
        val separator = id.lastIndexOf('-')
        if (separator <= STAR_REIFIER_PREFIX.length) return null
        return try {
            val bytes = java.util.Base64.getUrlDecoder().decode(id.substring(STAR_REIFIER_PREFIX.length, separator))
            if (checksum(bytes) != id.substring(separator + 1)) return null
            val text = String(bytes, Charsets.UTF_8)
            val cursor = intArrayOf(0)
            val value = decodeValue(text, cursor)
            if (cursor[0] != text.length) null else value as? Triple
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** True when [term] is (or contains, inside a triple term) a reifier produced by [reifierFor]. */
    fun mentionsStarReifier(term: RdfTerm?): Boolean = when (term) {
        is BlankNode -> quotedTripleOf(term.id) != null
        is TripleTerm -> mentionsStarReifier(term.triple.subject) || mentionsStarReifier(term.triple.obj)
        else -> false
    }

    /**
     * Store form of a subject for RDF-star capable stores: a reifier blank node becomes the quoted triple it stands
     * for, so writing the RDF 1.2 reified view back reproduces the original RDF-star statement instead of adding a
     * duplicate plain-blank-node copy.
     */
    fun toRdf4jStarResource(term: RdfResource): Resource =
        (term as? BlankNode)?.let { quotedTripleOf(it.id) } ?: toRdf4jResource(term)

    /** Store form of an object for RDF-star capable stores (reifiers inside triple terms become quoted triples). */
    fun toRdf4jStarValue(term: RdfTerm): Value = when (term) {
        is BlankNode -> quotedTripleOf(term.id) ?: toRdf4jValue(term)
        is TripleTerm -> valueFactory.createTriple(
            toRdf4jStarResource(term.triple.subject),
            toRdf4jIri(term.triple.predicate),
            toRdf4jStarValue(term.triple.obj),
        )
        else -> toRdf4jValue(term)
    }

    /**
     * Where quoted-triple subjects occur in a statement: [QuotedLevel.NONE] without any, [QuotedLevel.FLAT] when the
     * only one is the statement's own (un-nested) subject, [QuotedLevel.NESTED] otherwise.
     */
    fun quotedLevel(subject: Value, obj: Value): QuotedLevel {
        if (subject !is Triple && obj !is Triple) return QuotedLevel.NONE
        val quoted = LinkedHashSet<Triple>()
        collectQuotedSubjects(subject, true, quoted)
        collectQuotedSubjects(obj, false, quoted)
        return when {
            quoted.isEmpty() -> QuotedLevel.NONE
            quoted.size == 1 && subject is Triple && subject.subject !is Triple && subject.`object` !is Triple -> QuotedLevel.FLAT
            else -> QuotedLevel.NESTED
        }
    }

    private fun checksum(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(8)
        for (i in 0 until 4) hex.append(Character.forDigit((digest[i].toInt() shr 4) and 0xF, 16)).append(Character.forDigit(digest[i].toInt() and 0xF, 16))
        return hex.toString()
    }

    /** Injective, length-prefixed encoding: `I`/`B` + text, `L` + label + datatype, `G` + label + tag, `T` + s p o. */
    private fun encodeValue(value: Value, out: StringBuilder) {
        fun field(tag: Char, text: String) { out.append(tag).append(text.length).append(':').append(text) }
        when (value) {
            is IRI -> field('I', value.stringValue())
            is BNode -> field('B', value.id)
            is Rdf4jLiteral -> {
                val language = value.language.orElse(null)
                if (language != null) {
                    field('G', value.label)
                    field('_', language)
                } else {
                    field('L', value.label)
                    field('_', value.datatype.stringValue())
                }
            }
            is Triple -> {
                out.append('T')
                encodeValue(value.subject, out)
                encodeValue(value.predicate, out)
                encodeValue(value.`object`, out)
            }
            else -> throw IllegalArgumentException("Unknown RDF4J Value type: ${value.javaClass}")
        }
    }

    private fun decodeValue(text: String, cursor: IntArray): Value {
        fun field(expected: Char): String {
            require(text[cursor[0]] == expected) { "bad reifier encoding" }
            val colon = text.indexOf(':', cursor[0] + 1)
            require(colon > cursor[0] + 1) { "bad reifier encoding" }
            val length = text.substring(cursor[0] + 1, colon).toInt()
            require(length >= 0 && colon + 1 + length <= text.length) { "bad reifier encoding" }
            cursor[0] = colon + 1 + length
            return text.substring(colon + 1, colon + 1 + length)
        }
        return when (text[cursor[0]]) {
            'I' -> valueFactory.createIRI(field('I'))
            'B' -> valueFactory.createBNode(field('B'))
            'L' -> {
                val label = field('L')
                valueFactory.createLiteral(label, valueFactory.createIRI(field('_')))
            }
            'G' -> {
                val label = field('G')
                valueFactory.createLiteral(label, field('_'))
            }
            'T' -> {
                cursor[0]++
                val subject = decodeValue(text, cursor) as? Resource ?: throw IllegalArgumentException("bad reifier encoding")
                val predicate = decodeValue(text, cursor) as? IRI ?: throw IllegalArgumentException("bad reifier encoding")
                valueFactory.createTriple(subject, predicate, decodeValue(text, cursor))
            }
            else -> throw IllegalArgumentException("bad reifier encoding")
        }
    }

    /**
     * Maps one RDF4J statement to RDF 1.2 triples. Without RDF-star subjects this is the single converted
     * triple. Every quoted triple that occurs in subject position (also nested inside triple terms) is replaced
     * by its reifier `_:r` and additionally yields `_:r rdf:reifies <<( s p o )>>` — the RDF 1.2 reified form.
     * The converted statement itself is always the first element.
     *
     * @param seen quoted triples whose `rdf:reifies` triple was already emitted (for de-duplication across a
     *   stream of statements); `null` emits them every time.
     */
    fun triplesOf(subject: Resource, predicate: IRI, obj: Value, seen: MutableSet<Triple>? = null): List<RdfTriple> {
        val main = RdfTriple(fromRdf4jResource(subject), fromRdf4jIri(predicate), fromRdf4jValue(obj))
        if (subject !is Triple && obj !is Triple) return listOf(main)
        val quoted = LinkedHashSet<Triple>()
        collectQuotedSubjects(subject, true, quoted)
        collectQuotedSubjects(obj, false, quoted)
        if (quoted.isEmpty()) return listOf(main)
        val result = ArrayList<RdfTriple>(quoted.size + 1)
        result.add(main)
        for (triple in quoted) {
            if (seen == null || seen.add(triple)) {
                result.add(RdfTriple(reifierFor(triple), com.geoknoesis.kastor.rdf.vocab.RDF.reifies, fromRdf4jValue(triple)))
            }
        }
        return result
    }

    /** [triplesOf] for a whole [org.eclipse.rdf4j.model.Statement] (its context is ignored). */
    fun triplesOf(statement: org.eclipse.rdf4j.model.Statement, seen: MutableSet<Triple>? = null): List<RdfTriple> =
        triplesOf(statement.subject, statement.predicate, statement.`object`, seen)

    /** True if converting the statement yields more than one triple (it involves an RDF-star subject). */
    fun hasQuotedSubject(statement: org.eclipse.rdf4j.model.Statement): Boolean {
        val quoted = LinkedHashSet<Triple>()
        collectQuotedSubjects(statement.subject, true, quoted)
        collectQuotedSubjects(statement.`object`, false, quoted)
        return quoted.isNotEmpty()
    }

    private fun collectQuotedSubjects(value: Value, subjectPosition: Boolean, out: MutableSet<Triple>) {
        if (value !is Triple) return
        if (subjectPosition) out.add(value)
        collectQuotedSubjects(value.subject, true, out)
        collectQuotedSubjects(value.`object`, false, out)
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
        if (!lang.isNullOrEmpty()) {
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
