package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lexical codecs, ill-typed value policy, severity-aware `orThrow`, registry guard and closeable validators. */
class RuntimeContractsTest {

    private val xsd = "http://www.w3.org/2001/XMLSchema#"

    @Test
    fun `integer lexical forms accept a leading plus and reject non xsd syntax`() {
        assertEquals(1, XsdLiterals.int(TypedLiteral("+1", Iri("${xsd}int"))))
        assertEquals(-7L, XsdLiterals.long(TypedLiteral(" -7 ", Iri("${xsd}long"))))
        assertEquals(BigInteger.ONE, XsdLiterals.bigInteger(TypedLiteral("+1", XSD.integer)))
        assertNull(XsdLiterals.bigInteger(TypedLiteral("1_000", XSD.integer)))
        assertNull(XsdLiterals.int(TypedLiteral("0x10", Iri("${xsd}int"))))
    }

    @Test
    fun `decimal lexical forms follow xsd decimal`() {
        assertEquals(BigDecimal("1.50"), XsdLiterals.bigDecimal(TypedLiteral("+1.50", XSD.decimal)))
        assertEquals(BigDecimal("0.5"), XsdLiterals.bigDecimal(TypedLiteral(".5", XSD.decimal)))
        assertNull(XsdLiterals.bigDecimal(TypedLiteral("1e5", XSD.decimal)))
    }

    @Test
    fun `dates beyond year 9999 and before year 1 round-trip`() {
        val far = LocalDate.of(12345, 6, 7)
        val encoded = XsdLiterals.encode(far, XSD.date)
        assertEquals("12345-06-07", encoded.lexical)
        assertEquals(far, XsdLiterals.localDate(encoded))
        val bc = LocalDate.of(-44, 3, 15)
        assertEquals(bc, XsdLiterals.localDate(XsdLiterals.encode(bc, XSD.date)))
        assertEquals(LocalDate.of(2020, 1, 2), XsdLiterals.localDate(TypedLiteral("2020-01-02Z", XSD.date)))
        assertNull(XsdLiterals.localDate(TypedLiteral("2020-02-30", XSD.date)))
    }

    @Test
    fun `ill typed values throw by default and can be skipped`() {
        val bad = TypedLiteral("abc", XSD.integer)
        val e = assertFailsWith<MaterializationException> {
            MaterializationPolicy.illTyped(bad, "age <https://example.test/age>", "BigInteger")
        }
        assertTrue(e.message!!.contains("abc") && e.message!!.contains(XSD.integer.value) && e.message!!.contains("age"), e.message)
        val previous = MaterializationPolicy.illTypedValues
        try {
            MaterializationPolicy.illTypedValues = IllTypedValueHandling.SKIP
            assertNull(MaterializationPolicy.illTyped(bad, "age", "BigInteger"))
        } finally {
            MaterializationPolicy.illTypedValues = previous
        }
    }

    @Test
    fun `missing required values always throw a materialization exception naming the member`() {
        val e = assertFailsWith<MaterializationException> { MaterializationPolicy.missingRequired("title <https://example.test/title>") }
        assertTrue(e.message!!.contains("title <https://example.test/title>"), e.message)
        val previous = MaterializationPolicy.illTypedValues
        try {
            MaterializationPolicy.illTypedValues = IllTypedValueHandling.SKIP
            assertFailsWith<MaterializationException> { MaterializationPolicy.missingRequired("title") }
        } finally {
            MaterializationPolicy.illTypedValues = previous
        }
    }

    private fun violation(message: String, severity: ShaclSeverity) = ShaclViolation(
        focusNode = Iri("urn:focus"), shapeIri = Iri("urn:shape"), constraintIri = Iri("urn:component"),
        message = message, severity = severity,
    )

    @Test
    fun `orThrow only fails on violations unless a lower severity is requested`() {
        val warning = violation("w", ShaclSeverity.Warning)
        val info = violation("i", ShaclSeverity.Info)
        ValidationResult.Violations(listOf(warning, info)).orThrow()
        val onWarning = assertFailsWith<ValidationException> { ValidationResult.Violations(listOf(warning, info)).orThrow(ShaclSeverity.Warning) }
        assertEquals("w", onWarning.message)
        val onViolation = assertFailsWith<ValidationException> {
            ValidationResult.Violations(listOf(warning, violation("v", ShaclSeverity.Violation))).orThrow()
        }
        assertEquals("v", onViolation.message)
    }

    interface GuardedType

    @Test
    fun `registering a different factory for a registered type requires replace`() {
        val first: (RdfHandle) -> GuardedType = { error("first") }
        val second: (RdfHandle) -> GuardedType = { error("second") }
        try {
            OntoMapper.register(GuardedType::class.java, first)
            OntoMapper.register(GuardedType::class.java, first)
            val e = assertFailsWith<IllegalStateException> { OntoMapper.register(GuardedType::class.java, second) }
            assertTrue(e.message!!.contains(GuardedType::class.java.name) && e.message!!.contains("replace"), e.message)
            OntoMapper.register(GuardedType::class.java, replace = true, factory = second)
        } finally {
            OntoMapper.unregister(GuardedType::class.java)
        }
    }

    @Test
    fun `validation contexts are closeable with a no-op default`() {
        val context: AutoCloseable = object : ValidationContext {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult = ValidationResult.Ok
        }
        context.use { }
    }
}
