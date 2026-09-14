package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiteralValidationTest {

    @Test
    fun `language tags must be BCP 47 shaped`() {
        listOf("en", "en-GB", "zh-Hant-TW", "de-CH-1996", "x-private1", "abcdefghi", "en-abcdefghi").forEach {
            assertTrue(LiteralValidation.isWellFormedLanguageTag(it), it)
        }
        listOf("", "en-", "-en", "1en", "en_GB", "en--rtl", "en US", "\u00E9").forEach {
            assertFalse(LiteralValidation.isWellFormedLanguageTag(it), it)
        }
    }

    @Test
    fun `langString requires a tag and dirLangString requires tag and direction`() {
        val langString = LiteralValidation.RDF_LANG_STRING
        val dirLangString = LiteralValidation.RDF_DIR_LANG_STRING
        assertNotNull(LiteralValidation.problem(langString, null, false))
        assertNotNull(LiteralValidation.problem(langString, "", false))
        assertNull(LiteralValidation.problem(langString, "en", false))
        assertNotNull(LiteralValidation.problem(dirLangString, null, true))
        assertNotNull(LiteralValidation.problem(dirLangString, "ar", false))
        assertNull(LiteralValidation.problem(dirLangString, "ar", true))
        assertNotNull(LiteralValidation.problem(XSD.string.value, null, true), "direction without a tag")
        assertNotNull(LiteralValidation.problem(langString, "en_GB", false))
        assertNull(LiteralValidation.problem(XSD.string.value, null, false))
    }

    @Test
    fun `requireWellFormed checks Kastor terms`() {
        assertThrows(IllegalArgumentException::class.java) {
            LiteralValidation.requireWellFormed(TypedLiteral("x", Iri(LiteralValidation.RDF_LANG_STRING)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiteralValidation.requireWellFormed(TypedLiteral("x", Iri(LiteralValidation.RDF_DIR_LANG_STRING)))
        }
        assertDoesNotThrow { LiteralValidation.requireWellFormed(LangString("x", "en")) }
        assertDoesNotThrow { LiteralValidation.requireWellFormed(LangString("x", "ar", Direction.RTL)) }
        assertDoesNotThrow { LiteralValidation.requireWellFormed(Literal("1", XSD.integer)) }
        assertDoesNotThrow { LiteralValidation.requireWellFormed(Iri("urn:x")) }
    }
}
