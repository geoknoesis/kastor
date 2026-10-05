package com.geoknoesis.kastor.gen.processor.api.model

import java.math.BigDecimal

/** A SHACL numeric comparison, including the datatype needed for numeric promotion. */
public data class ShaclNumericBound(
    val kind: NumericBoundKind,
    val value: BigDecimal,
    val datatype: String = "http://www.w3.org/2001/XMLSchema#decimal",
)

/** The four SHACL value-range constraints. */
public enum class NumericBoundKind(public val shaclName: String) {
    MIN_INCLUSIVE("minInclusive"),
    MAX_INCLUSIVE("maxInclusive"),
    MIN_EXCLUSIVE("minExclusive"),
    MAX_EXCLUSIVE("maxExclusive"),
}

/** Typed constraints take precedence for their kind; legacy BigDecimal bounds denote decimals. */
internal fun numericBounds(
    typed: List<ShaclNumericBound>,
    minInclusive: BigDecimal?, maxInclusive: BigDecimal?,
    minExclusive: BigDecimal?, maxExclusive: BigDecimal?,
): List<ShaclNumericBound> = typed + listOfNotNull(
    minInclusive?.let { ShaclNumericBound(NumericBoundKind.MIN_INCLUSIVE, it) },
    maxInclusive?.let { ShaclNumericBound(NumericBoundKind.MAX_INCLUSIVE, it) },
    minExclusive?.let { ShaclNumericBound(NumericBoundKind.MIN_EXCLUSIVE, it) },
    maxExclusive?.let { ShaclNumericBound(NumericBoundKind.MAX_EXCLUSIVE, it) },
).filter { legacy -> typed.none { it.kind == legacy.kind } }
