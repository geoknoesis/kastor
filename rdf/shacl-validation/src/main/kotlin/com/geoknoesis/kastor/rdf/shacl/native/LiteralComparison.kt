package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private sealed interface ParsedXsdDateTime {
    data class Zoned(val dt: OffsetDateTime) : ParsedXsdDateTime

    data class Local(val dt: LocalDateTime) : ParsedXsdDateTime
}

private fun parseXsdDateTimeLexical(lexical: String): ParsedXsdDateTime? {
    val s = lexical.trim().replace('\u2212', '-')
    runCatching {
        ZonedDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME).toOffsetDateTime()
    }.getOrNull()?.let {
        return ParsedXsdDateTime.Zoned(it)
    }
    runCatching {
        LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull()?.let {
        return ParsedXsdDateTime.Local(it)
    }
    return null
}

private fun compareXsdDateTimeLexicals(leftLexical: String, rightLexical: String): Int? {
    val a = parseXsdDateTimeLexical(leftLexical) ?: return null
    val b = parseXsdDateTimeLexical(rightLexical) ?: return null
    return when {
        a is ParsedXsdDateTime.Zoned && b is ParsedXsdDateTime.Zoned -> a.dt.compareTo(b.dt)
        a is ParsedXsdDateTime.Local && b is ParsedXsdDateTime.Local -> a.dt.compareTo(b.dt)
        else -> null
    }
}

internal fun tryCompareLiterals(a: RdfTerm, b: RdfTerm): Int? {
    val la = a as? Literal ?: return null
    val lb = b as? Literal ?: return null
    if (la !is TypedLiteral || lb !is TypedLiteral) return null
    compareXsdDateTimeLexicals(la.lexical, lb.lexical)?.let { return it }

    val laDt = la.datatype == XSD.dateTime || la.datatype == XSD.dateTimeStamp
    val lbDt = lb.datatype == XSD.dateTime || lb.datatype == XSD.dateTimeStamp
    if (laDt && lbDt && la.datatype == lb.datatype) {
        return compareXsdDateTimeLexicals(la.lexical, lb.lexical)
    }
    val na = lexicalToBigDecimal(la) ?: return null
    val nb = lexicalToBigDecimal(lb) ?: return null
    return na.compareTo(nb)
}

private fun lexicalToBigDecimal(lit: TypedLiteral): BigDecimal? =
    when (lit.datatype) {
        XSD.integer,
        XSD.long,
        XSD.int,
        XSD.short,
        XSD.byte,
        XSD.nonNegativeInteger,
        XSD.positiveInteger,
        XSD.negativeInteger,
        XSD.nonPositiveInteger,
        XSD.decimal,
        XSD.double,
        XSD.float,
        -> runCatching { BigDecimal(lit.lexical) }.getOrNull()
        else -> null
    }

internal fun literalLess(a: RdfTerm, b: RdfTerm): Boolean =
    tryCompareLiterals(a, b)?.let { it < 0 } ?: false

internal fun literalLessOrEqual(a: RdfTerm, b: RdfTerm): Boolean =
    tryCompareLiterals(a, b)?.let { it <= 0 } ?: false

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

/**
 * XSD lexical validity for typed literals used by `sh:datatype` (ill-formed literals must fail even when
 * the RDF term carries the requested datatype IRI).
 */
internal fun typedLiteralLexicallyValidForShaclDatatype(lit: TypedLiteral): Boolean {
    val lex = lit.lexical.trim()
    return when (lit.datatype) {
        XSD.boolean ->
            when (lex.lowercase()) {
                "true", "false", "1", "0" -> true
                else -> false
            }
        XSD.integer,
        XSD.nonNegativeInteger,
        XSD.positiveInteger,
        XSD.negativeInteger,
        XSD.nonPositiveInteger,
        -> runCatching { BigInteger(lex) }.isSuccess
        XSD.long -> runCatching { lex.toLong() }.isSuccess
        XSD.int -> runCatching { lex.toInt() }.isSuccess
        XSD.short -> runCatching { lex.toShort() }.isSuccess
        XSD.byte -> runCatching { lex.toByte() }.isSuccess
        XSD.unsignedLong ->
            runCatching {
                val v = BigInteger(lex)
                v.signum() >= 0 && v <= BigInteger("18446744073709551615")
            }.getOrDefault(false)
        XSD.unsignedInt ->
            runCatching {
                val v = lex.toLong()
                v in 0L..4294967295L
            }.getOrDefault(false)
        XSD.unsignedShort ->
            runCatching {
                val v = lex.toInt()
                v in 0..65535
            }.getOrDefault(false)
        XSD.unsignedByte ->
            runCatching {
                val v = lex.toShort()
                v.toInt() in 0..255
            }.getOrDefault(false)
        XSD.decimal -> runCatching { BigDecimal(lex) }.isSuccess
        XSD.double ->
            lex.equals("NaN", ignoreCase = true) ||
                lex.equals("INF", ignoreCase = true) ||
                lex.equals("-INF", ignoreCase = true) ||
                runCatching { lex.toDouble() }.isSuccess
        XSD.float ->
            lex.equals("NaN", ignoreCase = true) ||
                lex.equals("INF", ignoreCase = true) ||
                lex.equals("-INF", ignoreCase = true) ||
                runCatching { lex.toFloat() }.isSuccess
        XSD.date -> runCatching { LocalDate.parse(lex) }.isSuccess
        XSD.time -> runCatching { LocalTime.parse(lex) }.isSuccess
        XSD.dateTime, XSD.dateTimeStamp -> parseXsdDateTimeLexical(lex) != null
        else -> true
    }
}
