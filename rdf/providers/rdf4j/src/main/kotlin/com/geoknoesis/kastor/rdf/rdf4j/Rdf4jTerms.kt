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
 * A reifier blank-node id (see [Rdf4jTerms.reifierFor]) exceeds the supported quoted-triple nesting depth
 * ([Rdf4jTerms.MAX_REIFIER_NESTING]) or, when decoding, the id length ([Rdf4jTerms.MAX_REIFIER_ID_LENGTH]). Raised
 * instead of decoding it, so that a crafted blank-node id cannot exhaust the stack or memory. Encoding only fails for
 * nesting beyond the limit: a triple too large for an encoded id gets a bounded hashed id instead.
 */
internal class ReifierLimitException(message: String) : IllegalArgumentException(message)

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
     * Deterministic reifier for an RDF-star quoted triple: the same triple always maps to the same blank node, and
     * the id can be resolved back to the triple, so lookups by a reifier are index lookups on its quoted triple
     * rather than scans (see [quotedTripleOf]).
     *
     * **Encoded ids** (the normal case): `kastor-star-<base64url(encoded triple)>-<8 hex digits of its SHA-256>`. The
     * id carries the triple itself. An id is only treated as a reifier when it decodes to a well-formed triple **and**
     * the checksum matches, so ordinary blank nodes whose ids merely start with `kastor-star-` are never mistaken for
     * reifiers.
     *
     * **Hashed ids** (triples whose encoded id would be longer than [MAX_REIFIER_ID_LENGTH], e.g. a quoted triple with
     * a literal of about 750 KB or more): `kastor-star-sha256-<64 hex digits>`, the SHA-256 of the same encoding. The
     * id is short whatever the size of the triple, so reading valid data never fails on an id limit. It does not carry
     * the triple: every hashed id this process produces is remembered (a bounded, softly referenced map from id to
     * triple), and [quotedTripleOf] answers from that map. An id that is not in the map (after a restart, or once it
     * was evicted) is resolved by [Rdf4jGraph] with one scan of the store for the triple with that hash; an id that
     * resolves nowhere is an ordinary blank node. The two forms cannot be confused: `sha256-<64 hex>` never passes the
     * 8-digit checksum of the encoded form.
     *
     * @throws ReifierLimitException when [triple] nests quoted triples deeper than [MAX_REIFIER_NESTING].
     */
    fun reifierFor(triple: Triple): BlankNode {
        val encoded = StringBuilder().also { encodeValue(triple, it, 1) }.toString().toByteArray(Charsets.UTF_8)
        // Unpadded base64 length, plus the prefix, the separator and the checksum.
        val idLength = STAR_REIFIER_PREFIX.length + (encoded.size * 4L + 2) / 3 + 1 + CHECKSUM_LENGTH
        if (idLength > MAX_REIFIER_ID_LENGTH) return hashedReifierFor(triple, encoded)
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
        return BlankNode("$STAR_REIFIER_PREFIX$payload-${checksum(encoded)}")
    }

    private const val CHECKSUM_LENGTH = 8
    private const val HASHED_MARKER = "sha256-"
    private const val HASH_LENGTH = 64

    /** Most hashed reifiers remembered at a time; older ones are resolved from the store again when needed. */
    private const val MAX_REMEMBERED_HASHED_REIFIERS = 64

    /** Hashed reifier id to its quoted triple, least recently used first; guarded by itself. */
    private val hashedReifiers =
        object : LinkedHashMap<String, java.lang.ref.SoftReference<Triple>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.lang.ref.SoftReference<Triple>>): Boolean =
                size > MAX_REMEMBERED_HASHED_REIFIERS
        }

    private fun hashedReifierFor(triple: Triple, encoded: ByteArray): BlankNode {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
        val id = STAR_REIFIER_PREFIX + HASHED_MARKER + hex(digest, digest.size)
        synchronized(hashedReifiers) {
            // A copy made of plain values, so the map never keeps a store's own value objects alive.
            if (hashedReifiers[id]?.get() == null) hashedReifiers[id] = java.lang.ref.SoftReference(detached(triple) as Triple)
        }
        return BlankNode(id)
    }

    private fun detached(value: Value): Value = when (value) {
        is Triple -> valueFactory.createTriple(detached(value.subject) as Resource, detached(value.predicate) as IRI, detached(value.`object`))
        is IRI -> valueFactory.createIRI(value.stringValue())
        is BNode -> valueFactory.createBNode(value.id)
        is Rdf4jLiteral -> {
            val language = value.language.orElse(null)
            if (language != null) valueFactory.createLiteral(value.label, language) else valueFactory.createLiteral(value.label, value.datatype)
        }
        else -> value
    }

    /** True when [id] has the form of a hashed reifier id (whether or not its triple is currently known). */
    fun isHashedReifierId(id: String): Boolean {
        val start = STAR_REIFIER_PREFIX.length + HASHED_MARKER.length
        if (id.length != start + HASH_LENGTH || !id.startsWith(STAR_REIFIER_PREFIX) || !id.startsWith(HASHED_MARKER, STAR_REIFIER_PREFIX.length)) return false
        for (i in start until id.length) if (id[i] !in '0'..'9' && id[i] !in 'a'..'f') return false
        return true
    }

    /**
     * Hashed reifier ids in subject position in [term] (a subject, or a triple term, at any depth) whose quoted triple
     * this process does not know. Blank nodes in object position are ordinary blank nodes and are not looked at.
     */
    fun unresolvedHashedReifiers(term: RdfTerm?, out: MutableSet<String>) {
        when (term) {
            is BlankNode -> if (isHashedReifierId(term.id) && quotedTripleOf(term.id) == null) out.add(term.id)
            is TripleTerm -> {
                unresolvedHashedReifiers(term.triple.subject, out)
                if (term.triple.obj is TripleTerm) unresolvedHashedReifiers(term.triple.obj, out)
            }
            else -> Unit
        }
    }

    /**
     * Remembers the hashed reifiers of the quoted triples in [value] (at any depth), so that their ids resolve.
     * Used to resolve a hashed id from the content of a store.
     */
    fun rememberHashedReifiers(value: Value) {
        if (value !is Triple) return
        try {
            reifierFor(value)
        } catch (_: ReifierLimitException) {
            // nested too deeply to have a reifier at all
            return
        }
        rememberHashedReifiers(value.subject)
        rememberHashedReifiers(value.`object`)
    }

    /** Forgets every remembered hashed reifier, as a new process would have (for tests). */
    internal fun forgetHashedReifiers() = synchronized(hashedReifiers) { hashedReifiers.clear() }

    /**
     * Deepest quoted-triple nesting a reifier id may encode. Deeper triples (whether written to the store or forged
     * into a blank-node id) fail with [ReifierLimitException] instead of recursing without bound.
     */
    const val MAX_REIFIER_NESTING: Int = 64

    /**
     * Longest encoded reifier id (in characters). A longer id is never produced (the triple gets a hashed id instead,
     * see [reifierFor]) and never decoded: [quotedTripleOf] rejects it with [ReifierLimitException].
     */
    const val MAX_REIFIER_ID_LENGTH: Int = 1 shl 20

    private fun requireNesting(depth: Int) {
        if (depth > MAX_REIFIER_NESTING) {
            throw ReifierLimitException("Quoted triple nesting in a reifier exceeds the limit of $MAX_REIFIER_NESTING levels")
        }
    }

    private fun requireIdLength(id: String) {
        if (id.length > MAX_REIFIER_ID_LENGTH) {
            throw ReifierLimitException("Reifier blank-node id length ${id.length} exceeds the limit of $MAX_REIFIER_ID_LENGTH characters")
        }
    }

    /**
     * The quoted triple a reifier id produced by [reifierFor] stands for, or null when [id] is not such a reifier
     * (for a hashed id: when its triple is not currently remembered, see [reifierFor]).
     * @throws ReifierLimitException when [id] carries the reifier prefix but is longer than [MAX_REIFIER_ID_LENGTH] or
     *   encodes a triple nested deeper than [MAX_REIFIER_NESTING].
     */
    fun quotedTripleOf(id: String): Triple? {
        if (!id.startsWith(STAR_REIFIER_PREFIX)) return null
        if (isHashedReifierId(id)) return synchronized(hashedReifiers) { hashedReifiers[id]?.get() }
        requireIdLength(id)
        val separator = id.lastIndexOf('-')
        if (separator <= STAR_REIFIER_PREFIX.length) return null
        return try {
            val bytes = java.util.Base64.getUrlDecoder().decode(id.substring(STAR_REIFIER_PREFIX.length, separator))
            if (checksum(bytes) != id.substring(separator + 1)) return null
            val text = String(bytes, Charsets.UTF_8)
            val cursor = intArrayOf(0)
            val value = decodeValue(text, cursor, 0)
            if (cursor[0] != text.length) null else value as? Triple
        } catch (e: ReifierLimitException) {
            throw e
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** True when the subject [term] is a reifier produced by [reifierFor]: its store form is a quoted triple. */
    fun mentionsStarReifier(term: RdfResource?): Boolean = term is BlankNode && quotedTripleOf(term.id) != null

    /**
     * True when the store form of the object [term] differs from its plain form: it is a triple term with a reifier
     * in a subject position (at any depth). A reifier blank node that is itself an object - of the statement or of a
     * triple term - is an ordinary blank node there, see [toRdf4jStarValue].
     */
    fun objectMentionsStarReifier(term: RdfTerm?): Boolean =
        term is TripleTerm && (mentionsStarReifier(term.triple.subject) || objectMentionsStarReifier(term.triple.obj))

    /**
     * Store form of a subject for RDF-star capable stores: a reifier blank node becomes the quoted triple it stands
     * for, so writing the RDF 1.2 reified view back reproduces the original RDF-star statement instead of adding a
     * duplicate plain-blank-node copy.
     */
    fun toRdf4jStarResource(term: RdfResource): Resource =
        (term as? BlankNode)?.let { quotedTripleOf(it.id) } ?: toRdf4jResource(term)

    /**
     * Store form of an object for RDF-star capable stores: reifiers in a **subject** position inside triple terms
     * become quoted triples (they are read back as reifiers, see [fromRdf4jResource]).
     *
     * A reifier blank node in **object** position - the object of the statement, or of a triple term - stays that
     * blank node. Stored as the quoted triple it would be read back as a triple term (see [fromRdf4jValue]), a
     * different triple from the one written: an RDF4J triple value in object position *is* a triple term, and only in
     * subject position (which RDF 1.2 cannot represent) does a reifier stand for it.
     */
    fun toRdf4jStarValue(term: RdfTerm): Value = when (term) {
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

    private fun checksum(bytes: ByteArray): String =
        hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes), CHECKSUM_LENGTH / 2)

    private fun hex(bytes: ByteArray, count: Int): String {
        val hex = StringBuilder(count * 2)
        for (i in 0 until count) hex.append(Character.forDigit((bytes[i].toInt() shr 4) and 0xF, 16)).append(Character.forDigit(bytes[i].toInt() and 0xF, 16))
        return hex.toString()
    }

    /** Injective, length-prefixed encoding: `I`/`B` + text, `L` + label + datatype, `G` + label + tag, `T` + s p o. */
    private fun encodeValue(value: Value, out: StringBuilder, depth: Int) {
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
                // [depth] counts the triples enclosing (and including) this one; the reified triple itself is depth 1.
                requireNesting(depth)
                out.append('T')
                encodeValue(value.subject, out, depth + 1)
                encodeValue(value.predicate, out, depth + 1)
                encodeValue(value.`object`, out, depth + 1)
            }
            else -> throw IllegalArgumentException("Unknown RDF4J Value type: ${value.javaClass}")
        }
    }

    private fun decodeValue(text: String, cursor: IntArray, depth: Int): Value {
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
                requireNesting(depth + 1)
                cursor[0]++
                val subject = decodeValue(text, cursor, depth + 1) as? Resource ?: throw IllegalArgumentException("bad reifier encoding")
                val predicate = decodeValue(text, cursor, depth + 1) as? IRI ?: throw IllegalArgumentException("bad reifier encoding")
                valueFactory.createTriple(subject, predicate, decodeValue(text, cursor, depth + 1))
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
