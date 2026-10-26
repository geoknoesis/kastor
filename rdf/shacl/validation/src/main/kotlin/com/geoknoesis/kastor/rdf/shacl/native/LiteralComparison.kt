package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.xsd.XsdLexical
import com.geoknoesis.kastor.rdf.xsd.XsdNumeric
import com.geoknoesis.kastor.rdf.xsd.XsdTemporal
import com.geoknoesis.kastor.rdf.xsd.XsdTemporals
import com.geoknoesis.kastor.rdf.xsd.XsdWork

/*
 * Datatype-aware literal ordering for sh:minInclusive / sh:maxInclusive / sh:minExclusive / sh:maxExclusive /
 * sh:lessThan / sh:lessThanOrEquals, following the SPARQL 1.1 operator mapping for `<`:
 *  - numerics (xsd:integer and all derived types incl. unsigned, xsd:decimal, xsd:float, xsd:double) with
 *    numeric type promotion;
 *  - xsd:string (code point order);
 *  - xsd:boolean (false < true);
 *  - xsd:dateTime / xsd:dateTimeStamp, xsd:date and xsd:time, each only within its own family, using the XSD
 *    partial order for values with and without timezone (±14:00 indeterminacy window).
 * Anything else (language-tagged strings, ill-formed lexical forms, NaN, mixed families) is incomparable,
 * which the SHACL comparison components treat as a violation: the comparison cannot be made true.
 * sh:in and sh:hasValue do not use this order: SHACL defines them by RDF term equality.
 *
 * Values are parsed by the shared linear-time XSD parser of :rdf:core (com.geoknoesis.kastor.rdf.xsd): decimals are
 * compared by their digits and fractional seconds as digit strings, so a literal of millions of digits costs time
 * linear in its length (`BigDecimal(String)` takes 21 s for a million digits).
 */

/** A literal's value in a space that [compareKeys] orders, parsed once. */
private sealed interface OrderKey {
    class Numeric(val value: XsdNumeric) : OrderKey
    class Text(val value: String) : OrderKey
    class Bool(val value: Boolean) : OrderKey
    class Temporal(val value: XsdTemporal) : OrderKey
}

/** The order key of [term], or `null` when it is not a literal that SPARQL `<` compares (or is ill-formed). */
private fun orderKey(term: RdfTerm): OrderKey? {
    val lit = term as? Literal ?: return null
    if (lit is LangString) return null
    val dt = lit.datatype.value
    return when {
        lit.datatype == XSD.string -> OrderKey.Text(lit.lexical)
        lit.datatype == XSD.boolean -> XsdLexical.parseBoolean(lit.lexical)?.let(OrderKey::Bool)
        else -> XsdNumeric.parse(dt, lit.lexical)?.let(OrderKey::Numeric)
            ?: XsdTemporals.parse(dt, lit.lexical)?.takeIf { XsdTemporals.family(it.kind) != null }?.let(OrderKey::Temporal)
    }
}

private fun compareKeys(a: OrderKey?, b: OrderKey?): Int? = when {
    a is OrderKey.Numeric && b is OrderKey.Numeric -> XsdNumeric.compare(a.value, b.value)
    a is OrderKey.Text && b is OrderKey.Text -> compareCodePoints(a.value, b.value)
    a is OrderKey.Bool && b is OrderKey.Bool -> a.value.compareTo(b.value)
    a is OrderKey.Temporal && b is OrderKey.Temporal -> XsdTemporals.compare(a.value, b.value)
    else -> null
}

/**
 * Code point order of two strings without copying them: UTF-16 order differs from it only where a surrogate meets a
 * character in U+E000..U+FFFF, so those are swapped before comparing the first differing units.
 */
private fun compareCodePoints(a: String, b: String): Int {
    val common = minOf(a.length, b.length)
    XsdWork.charge(common)
    for (i in 0 until common) {
        val x = a[i]
        val y = b[i]
        if (x != y) return codePointRank(x).compareTo(codePointRank(y)).coerceIn(-1, 1)
    }
    return a.length.compareTo(b.length).coerceIn(-1, 1)
}

private fun codePointRank(c: Char): Int = when {
    c.code >= 0xE000 -> c.code - 0x800
    c.code >= 0xD800 -> c.code + 0x2000
    else -> c.code
}

// --- public API ---------------------------------------------------------------------------------------------------

/** Three-way comparison per the SPARQL `<` operator mapping, or `null` when the terms are incomparable. */
internal fun tryCompareLiterals(a: RdfTerm, b: RdfTerm): Int? {
    val ka = orderKey(a) ?: return null
    return compareKeys(ka, orderKey(b))
}

internal fun literalLess(a: RdfTerm, b: RdfTerm): Boolean =
    tryCompareLiterals(a, b)?.let { it < 0 } ?: false

internal fun literalLessOrEqual(a: RdfTerm, b: RdfTerm): Boolean =
    tryCompareLiterals(a, b)?.let { it <= 0 } ?: false

/** A value space in which [tryCompareLiterals] is a total order (and never null), or null for any other term. */
private fun totalOrderClass(key: OrderKey?): Any? = when (key) {
    null -> null
    // Floats and doubles are one total order: widening a float is exact and keeps the order.
    is OrderKey.Numeric -> if (key.value.isNaN) null else if (key.value is XsdNumeric.Exact) "exact" else "approximate"
    is OrderKey.Text -> "string"
    is OrderKey.Bool -> "boolean"
    // Values with and without a timezone are only partially ordered (XSD 1.1 §E.3.3).
    is OrderKey.Temporal -> XsdTemporals.family(key.value.kind) to (key.value.timezoneMinutes != null)
}

/**
 * The (value, other) pairs for which `value < other` ([strict], `sh:lessThan`) or `value <= other`
 * (`sh:lessThanOrEquals`) does not hold; SHACL reports one result per such pair.
 *
 * When every term lies in one value space that [tryCompareLiterals] orders totally (exact or approximate numbers,
 * strings, booleans, or date/time values of one family that all have or all lack a timezone), [others] is sorted once
 * and each value is compared from the smallest other value upwards until it is below one, since the violating others
 * form a prefix: O((n + m) log m + results) comparisons instead of n × m. Otherwise every pair is compared.
 * At most [limit] pairs are returned (the first ones, in value order). [onCompare] observes the comparisons (the
 * budget, instrumentation). Every term is parsed once, not once per comparison.
 */
internal fun orderViolations(
    values: List<RdfTerm>,
    others: List<RdfTerm>,
    strict: Boolean,
    limit: Int = Int.MAX_VALUE,
    onCompare: () -> Unit = {},
): List<Pair<RdfTerm, RdfTerm>> {
    if (values.isEmpty() || others.isEmpty()) return emptyList()
    val valueKeys = values.map(::orderKey)
    val otherKeys = others.map(::orderKey)
    fun ordered(v: OrderKey?, w: OrderKey?): Boolean {
        onCompare()
        val c = compareKeys(v, w) ?: return false
        return if (strict) c < 0 else c <= 0
    }
    val out = ArrayList<Pair<RdfTerm, RdfTerm>>()
    val space = totalOrderClass(otherKeys[0])
    if (space == null || otherKeys.any { totalOrderClass(it) != space } || valueKeys.any { totalOrderClass(it) != space }) {
        for (i in values.indices) for (j in others.indices) {
            if (!ordered(valueKeys[i], otherKeys[j])) out.add(values[i] to others[j])
            if (out.size >= limit) return out
        }
        return out
    }
    val sorted = others.indices.sortedWith { x, y ->
        onCompare()
        compareKeys(otherKeys[x], otherKeys[y]) ?: 0
    }
    for (i in values.indices) {
        for (j in sorted) {
            if (ordered(valueKeys[i], otherKeys[j])) break
            out.add(values[i] to others[j])
            if (out.size >= limit) return out
        }
    }
    return out
}

internal fun satisfiesMinInclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c >= 0
}

internal fun satisfiesMaxInclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c <= 0
}

internal fun satisfiesMinExclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c > 0
}

internal fun satisfiesMaxExclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c < 0
}

