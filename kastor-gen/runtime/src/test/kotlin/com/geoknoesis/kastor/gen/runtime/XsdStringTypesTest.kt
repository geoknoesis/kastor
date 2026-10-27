package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.TypedLiteral
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Generated `sh:datatype` checks ([XsdLiterals.hasDatatype]) reject the ill-formed literals of the string, binary,
 * name and URI datatypes that the Kastor SHACL validator rejects: both use the shared parser of `:rdf:core`
 * (`com.geoknoesis.kastor.rdf.xsd.XsdStrings`). Before, `"zz"^^xsd:hexBinary` passed generated validation.
 */
class XsdStringTypesTest {
    private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#$local")
    private fun lit(lexical: String, local: String): Literal = TypedLiteral(lexical, xsd(local))

    private val illFormed = listOf(
        lit("zz", "hexBinary"), lit("abc", "hexBinary"),
        lit("@@", "base64Binary"), lit("YQ=", "base64Binary"), lit("YR==", "base64Binary"),
        lit("not a tag!", "language"), lit("en-", "language"), lit("abcdefghi", "language"),
        lit("1abc", "NCName"), lit("a:b", "NCName"), lit("", "Name"), lit("a b", "NMTOKEN"),
        lit("a\tb", "normalizedString"), lit(" a", "token"), lit("a  b", "token"),
        lit("http://e/%zz", "anyURI"), lit("a:b:c", "QName"), lit("", "IDREFS"),
    )

    private val wellFormed = listOf(
        lit("0aFF", "hexBinary"), lit("", "hexBinary"), lit("YQ==", "base64Binary"), lit("YWJj", "base64Binary"),
        lit("en-GB", "language"), lit("x-private", "language"), lit("abc", "NCName"), lit("a:b", "Name"),
        lit("a b", "NMTOKENS"), lit("a b", "token"), lit("http://e/%41", "anyURI"), lit("p:local", "QName"),
        lit("anything\u000B", "string"),
    )

    @Test
    fun `ill-formed string binary and name literals fail sh datatype`() {
        for (value in illFormed) {
            assertFalse(XsdLiterals.isWellFormed(value), "$value")
            assertFalse(XsdLiterals.hasDatatype(value, value.datatype), "$value")
        }
    }

    @Test
    fun `well-formed ones pass`() {
        for (value in wellFormed) {
            assertTrue(XsdLiterals.isWellFormed(value), "$value")
            assertTrue(XsdLiterals.hasDatatype(value, value.datatype), "$value")
        }
    }

    @Test
    fun `encode refuses a value that is not in the lexical space`() {
        assertThrows<IllegalArgumentException> { XsdLiterals.encode("zz", xsd("hexBinary")) }
        assertThrows<IllegalArgumentException> { XsdLiterals.encode("not a tag!", xsd("language")) }
    }
}
