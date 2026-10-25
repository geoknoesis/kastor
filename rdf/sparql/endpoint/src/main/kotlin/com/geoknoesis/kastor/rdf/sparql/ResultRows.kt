package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * The variables a row binds, in the order the row binds them. Rows that bind the same variables in the same order
 * share one shape, so a result of a million rows holds its variable names (and their [Set]) once, not once per row.
 */
internal class RowShape(private val names: Array<String>) {
    /** The names as the [BindingSet.getVariableNames] of every row of this shape: one unmodifiable set. */
    val nameSet: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(names.asList()))

    /** Index of each name for wide rows; narrow rows are searched linearly, which is faster and allocates nothing. */
    private val index: Map<String, Int>? = if (names.size > LINEAR_SEARCH_NAMES) names.withIndex().associate { it.value to it.index } else null

    val size: Int get() = names.size

    fun indexOf(name: String): Int {
        index?.let { return it[name] ?: -1 }
        for (i in names.indices) if (names[i] == name) return i
        return -1
    }

    fun nameAt(i: Int): String = names[i]

    fun matches(candidate: List<String>): Boolean {
        if (candidate.size != names.size) return false
        for (i in names.indices) if (names[i] != candidate[i]) return false
        return true
    }

    private companion object {
        const val LINEAR_SEARCH_NAMES = 8
    }
}

/** A decoded result row: its [shape] and one term per variable of the shape. Immutable. */
internal class CompactBindingSet(private val shape: RowShape, private val values: Array<RdfTerm>) : BindingSet {
    override fun get(variable: String): RdfTerm? = shape.indexOf(variable).let { if (it < 0) null else values[it] }

    override fun getVariableNames(): Set<String> = shape.nameSet

    override fun hasBinding(variable: String): Boolean = shape.indexOf(variable) >= 0

    override fun toString(): String = (0 until shape.size).joinToString(", ", "{", "}") { "${shape.nameAt(it)}=${values[it]}" }
}

/** The row that binds nothing: one instance for every such row (a result of `{}` rows held one map per row). */
internal object EmptyBindingSet : BindingSet {
    override fun get(variable: String): RdfTerm? = null

    override fun getVariableNames(): Set<String> = emptySet()

    override fun hasBinding(variable: String): Boolean = false

    override fun toString(): String = "{}"
}

/**
 * Builds the rows of one result: shares what repeats across rows (variable names, row shapes, datatype IRIs and
 * language tags) and estimates the heap each row retains ([lastRowBytes]), so that a buffered result can be held to
 * [SparqlEndpointConfig.maxBufferedResultBytes].
 *
 * The caches are bounded by entries ([MAX_CANONICAL], [MAX_SHAPES]), by the length of what they hold
 * ([MAX_CANONICAL_CHARS] per string and per shape) and by one byte budget for all of them ([MAX_CACHE_BYTES]): a
 * hostile result with ever new or huge names only stops being shared, it cannot grow them. What a cache keeps is
 * charged, once, to the row that first needed it, so the caches count against the heap estimate like the rows.
 */
internal class RowAssembler {
    private val canonical = HashMap<String, String>()
    private val datatypes = HashMap<String, Iri>()
    private val shapes = HashMap<List<String>, RowShape>()
    private var lastShape: RowShape? = null

    private var names = ArrayList<String>()
    private var terms = ArrayList<RdfTerm>()
    private var seen = HashSet<String>()
    private var bytes = 0L

    /** Estimated heap of everything the caches hold; nothing more is cached once it reaches [MAX_CACHE_BYTES]. */
    var cacheBytes = 0L
        private set

    /** Cache growth not yet charged to a row: charged to the next row [finish] returns. */
    private var uncharged = 0L

    /** Estimated heap retained by the row [finish] returned last, the caller's reference to it included. */
    var lastRowBytes = 0L
        private set

    /** Declares the variables of `head.vars`, whose strings the rows then share. */
    fun declare(vars: Collection<String>) {
        for (name in vars) share(name)
    }

    /** [text] itself, or an equal string met before: short strings that repeat are kept once. */
    fun share(text: String): String {
        if (text.length > MAX_CANONICAL_CHARS) return text
        canonical[text]?.let { return it }
        if (canonical.size < MAX_CANONICAL && admit(CACHE_ENTRY + stringBytes(text))) canonical[text] = text
        return text
    }

    /** The datatype [Iri] of [text], validated once per distinct datatype that is short enough to be cached. */
    fun datatype(text: String): Iri {
        datatypes[text]?.let { return it }
        val shared = share(text)
        val iri = Iri(shared)
        // The string itself is charged by share() when it is cached; a datatype that is not is charged to its term.
        if (canonical[shared] === shared && datatypes.size < MAX_CANONICAL && admit(CACHE_ENTRY + BOXED_TERM)) datatypes[shared] = iri
        return iri
    }

    /** Whether the caches may grow by [cost] bytes; if so, the growth is recorded to be charged to a row. */
    private fun admit(cost: Long): Boolean {
        if (cacheBytes + cost > MAX_CACHE_BYTES) return false
        cacheBytes += cost
        uncharged += cost
        return true
    }

    /** Starts a row. */
    fun begin() {
        // One unusually wide row must not keep large scratch tables alive for the rest of the stream.
        if (names.size > RETAINED_SCRATCH) {
            names = ArrayList()
            terms = ArrayList()
            seen = HashSet()
        } else {
            names.clear()
            terms.clear()
            seen.clear()
        }
        bytes = 0
    }

    /** Whether the row being built already binds [name]. */
    fun binds(name: String): Boolean = name in seen

    /** Adds the binding of [name] (a name [binds] said is new) to the row being built. */
    fun add(name: String, term: RdfTerm) {
        val shared = share(name)
        seen += shared
        names += shared
        terms += term
        bytes += BINDING_SLOT + termBytes(term)
    }

    /** The row built since [begin]. */
    fun finish(): BindingSet {
        if (names.isEmpty()) {
            lastRowBytes = ROW_REFERENCE + takeUncharged()
            return EmptyBindingSet
        }
        val shape = shapeOf(names)
        lastRowBytes = ROW_REFERENCE + ROW_OBJECT + bytes + takeUncharged()
        return CompactBindingSet(shape, terms.toTypedArray())
    }

    private fun shapeOf(names: List<String>): RowShape {
        lastShape?.let { if (it.matches(names)) return it }
        val shape = shapes[names] ?: RowShape(names.toTypedArray()).also { created ->
            // A shape is cached when it is small and the caches have room (charged once, with the key that holds it);
            // otherwise it is held by its row alone and charged to the row.
            val cost = 2 * shapeBytes(names)
            val small = names.sumOf { it.length } <= MAX_CANONICAL_CHARS
            if (small && shapes.size < MAX_SHAPES && admit(cost)) shapes[names.toList()] = created else bytes += shapeBytes(names)
        }
        lastShape = shape
        return shape
    }

    private fun termBytes(term: RdfTerm): Long =
        when (term) {
            is LangString -> LANG_STRING_OBJECT + stringBytes(term.lexical) + sharedBytes(term.lang) +
                (if (term.normalizedLang === term.lang) 0 else stringBytes(term.normalizedLang))
            is Literal -> TYPED_LITERAL_OBJECT + stringBytes(term.lexical) + datatypeBytes(term.datatype.value)
            is Iri -> BOXED_TERM + stringBytes(term.value)
            is BlankNode -> BOXED_TERM + stringBytes(term.id)
            else -> BOXED_TERM + stringBytes(term.toString())
        }

    private fun takeUncharged(): Long = uncharged.also { uncharged = 0 }

    /** A string the caches may hold: free when it is the cached instance (charged when it was cached). */
    private fun sharedBytes(text: String): Long = if (canonical[text] === text) 0 else stringBytes(text)

    /** A datatype IRI: free when it is `xsd:string` (a constant) or the cached instance. */
    private fun datatypeBytes(text: String): Long =
        if (text === XSD.string.value || datatypes[text]?.value === text) 0 else stringBytes(text)

    private fun shapeBytes(names: List<String>): Long = SHAPE_OBJECT + names.sumOf { NAME_ENTRY + stringBytes(it) }

    internal companion object {
        /** Distinct short strings, datatypes and row shapes kept for sharing. */
        const val MAX_CANONICAL = 4096
        const val MAX_CANONICAL_CHARS = 1024
        const val MAX_SHAPES = 256
        const val RETAINED_SCRATCH = 1024

        /** One budget for everything the caches hold. */
        const val MAX_CACHE_BYTES = 4L * 1024 * 1024
        const val CACHE_ENTRY = 48L

        // Estimates for a 64-bit JVM, rounded up so that the estimate is not below the real retained size: object
        // headers of 16 bytes, 8-byte references, strings of two bytes per character (compact Latin-1 strings use one).
        const val ROW_REFERENCE = 12L // the list slot that holds the row, with the list's growth slack
        const val ROW_OBJECT = 48L // the row and its term array
        const val BINDING_SLOT = 8L
        const val BOXED_TERM = 16L
        const val TYPED_LITERAL_OBJECT = 24L
        const val LANG_STRING_OBJECT = 32L
        const val SHAPE_OBJECT = 160L
        const val NAME_ENTRY = 64L

        fun stringBytes(text: String): Long = 40L + 2L * text.length
    }
}
