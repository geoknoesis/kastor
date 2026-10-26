package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi

/**
 * The XSD lexical-space check shared by the SHACL validator, Kastor Gen, the reasoners and the providers: one linear
 * pass per literal for every numeric, boolean, date/time and duration datatype of XML Schema 1.1 Part 2 (with the
 * value ranges of the integer types). See [XsdNumbers], [XsdTemporals] and [isDuration] for the grammars.
 */
@InternalKastorApi
object XsdLexical {

    /** The three duration datatypes. */
    @InternalKastorApi
    enum class DurationKind(val localName: String) {
        DURATION("duration"),
        DAY_TIME("dayTimeDuration"),
        YEAR_MONTH("yearMonthDuration"),
    }

    private val DURATIONS = DurationKind.entries.associateBy { XSD_NAMESPACE + it.localName }

    /**
     * Whether [lexical] is in the lexical space of [datatypeIri] (and, for an integer type, in its value range);
     * `null` when the datatype is not one this object checks (strings, binary types, non-XSD datatypes).
     * The lexical form is matched as it is: callers that collapse white space trim it first.
     */
    @JvmStatic
    fun isValid(datatypeIri: String, lexical: CharSequence): Boolean? {
        if (!datatypeIri.startsWith(XSD_NAMESPACE)) return null
        XsdNumbers.integerRange(datatypeIri)?.let { range -> return XsdNumbers.parseInteger(lexical)?.let { it in range } ?: false }
        XsdTemporalKind.of(datatypeIri)?.let { return XsdTemporals.parse(it, lexical) != null }
        DURATIONS[datatypeIri]?.let { return isDuration(it, lexical) }
        return when (datatypeIri) {
            XsdNumbers.DECIMAL -> XsdNumbers.parseDecimal(lexical) != null
            XsdNumbers.FLOAT, XsdNumbers.DOUBLE -> XsdNumbers.parseFloating(lexical) != null
            XSD_NAMESPACE + "boolean" -> parseBoolean(lexical) != null
            else -> null
        }
    }

    /** The value of an `xsd:boolean` lexical form (`true`, `false`, `1`, `0`), or `null`. */
    @JvmStatic
    fun parseBoolean(lexical: CharSequence): Boolean? = when (lexical.takeIf { it.length <= 5 }?.toString()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    /**
     * Whether [lexical] is a duration of [kind] (§3.3.6, §3.4.26, §3.4.27): `-?P`, then `nY`, `nM`, `nD` in that
     * order, then optionally `T` followed by `nH`, `nM`, `n(.n)?S` in that order, with at least one component and at
     * least one after `T`. A `dayTimeDuration` has no `Y` or date `M`; a `yearMonthDuration` only `Y` and `M`.
     */
    @JvmStatic
    fun isDuration(kind: DurationKind, lexical: CharSequence): Boolean {
        XsdWork.charge(lexical.length)
        val s = lexical
        var i = if (s.startsWith("-")) 1 else 0
        if (i >= s.length || s[i++] != 'P') return false
        var next = 0 // Index in DESIGNATORS of the first designator still allowed.
        var components = 0
        var afterT = -1
        while (i < s.length) {
            if (s[i] == 'T') {
                if (afterT >= 0 || kind == DurationKind.YEAR_MONTH) return false
                afterT = components
                next = TIME_START
                i++
                continue
            }
            val numberEnd = durationNumber(s, i)?.takeIf { it < s.length } ?: return false
            val designator = DESIGNATORS.indexOf(s[numberEnd], next, afterT >= 0)
            if (designator < 0 || !allowed(kind, designator) || (designator != SECONDS && hasPoint(s, i, numberEnd))) return false
            next = designator + 1
            components++
            i = numberEnd + 1
        }
        return components > 0 && (afterT < 0 || components > afterT)
    }

    /** `Y M D` before `T`, `H M S` after it. */
    private const val DESIGNATORS = "YMDHMS"
    private const val TIME_START = 3
    private const val SECONDS = 5

    /** The index of [c] in [this] at or after [from], in the date part or (when [time]) the time part only. */
    private fun String.indexOf(c: Char, from: Int, time: Boolean): Int {
        val range = if (time) maxOf(from, TIME_START) until length else from until TIME_START
        return range.firstOrNull { this[it] == c } ?: -1
    }

    private fun allowed(kind: DurationKind, designator: Int): Boolean = when (kind) {
        DurationKind.DURATION -> true
        DurationKind.DAY_TIME -> designator >= 2
        DurationKind.YEAR_MONTH -> designator <= 1
    }

    /** The end of `[0-9]+(\.[0-9]+)?` at [from], or `null`. */
    private fun durationNumber(s: CharSequence, from: Int): Int? {
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == from) return null
        if (i < s.length && s[i] == '.') {
            val start = ++i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start) return null
        }
        return i
    }

    private fun hasPoint(s: CharSequence, from: Int, end: Int): Boolean = (from until end).any { s[it] == '.' }
}
