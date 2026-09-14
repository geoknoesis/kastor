package com.geoknoesis.kastor.rdf.reasoning.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** XML Schema 1.1 lexical spaces: valid literals must never be reported as inconsistencies. */
class XsdLexicalFormsTest {
    private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#$local")

    private val valid = listOf(
        "24:00:00" to "time",
        "23:59:59.999Z" to "time",
        "2024-01-01T24:00:00Z" to "dateTime",
        "2024-01-01T24:00:00.000" to "dateTime",
        "12024-05-01" to "date",
        "-0044-03-15" to "date",
        "0000-02-29" to "date",
        "2024-02-29" to "date",
        "2024-02-28+14:00" to "date",
        "2024-06-30T10:00:00-05:00" to "dateTimeStamp",
        " 42 " to "integer",
        "\t+7\n" to "int",
        " true " to "boolean",
        " 1.5 " to "decimal",
        " -INF " to "double",
        "4294967295" to "unsignedInt",
        "  x  " to "string",
    )

    private val invalid = listOf(
        "24:00:01" to "time",
        "24:30:00" to "time",
        "2024-01-01T25:00:00" to "dateTime",
        "2023-02-29" to "date",
        "2024-04-31" to "date",
        "2024-13-01" to "date",
        "999-01-01" to "date",
        "2024-01-01+15:00" to "date",
        "2024-06-30T10:00:00" to "dateTimeStamp",
        "1 2" to "integer",
        " x " to "integer",
        "128" to "byte",
        "-1" to "unsignedByte",
        "yes" to "boolean",
    )

    @Test
    fun `valid XSD 1 dot 1 lexical forms are well typed`() {
        for ((lexical, type) in valid) assertTrue(XsdLexicalForms.isWellTyped(lexical, xsd(type)), "\"$lexical\"^^xsd:$type")
    }

    @Test
    fun `invalid lexical forms are ill typed`() {
        for ((lexical, type) in invalid) assertFalse(XsdLexicalForms.isWellTyped(lexical, xsd(type)), "\"$lexical\"^^xsd:$type")
    }

    @Test
    fun `the memory reasoner reports no false inconsistencies`() {
        val s = Iri("http://example.org/s")
        val p = Iri("http://example.org/p")
        val reasoner = MemoryReasoner(ReasonerConfig.rdfs())
        assertTrue(reasoner.isConsistent(MemoryGraph(valid.map { (lexical, type) -> RdfTriple(s, p, TypedLiteral(lexical, xsd(type))) })))
        assertFalse(reasoner.isConsistent(MemoryGraph(listOf(RdfTriple(s, p, TypedLiteral("2023-02-29", xsd("date")))))))
    }
}
