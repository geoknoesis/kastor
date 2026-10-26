package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger

/** [mergeRecord] keeps every term a record could not have changed and edits nothing when it fails. */
class RecordWriteTest {

    private val s = Iri("urn:s")
    private val title = Iri("urn:title")
    private val count = Iri("urn:count")
    private val type = Iri("urn:Type")

    private fun strings(values: List<String>, single: Boolean) = RecordField(
        title, values, single, { (it as? Literal)?.let(XsdLiterals::string) }, { Literal(it) },
    )

    private fun integers(values: List<BigInteger>) = RecordField(
        count, values, true, { (it as? Literal)?.let(XsdLiterals::bigInteger) }, { XsdLiterals.encode(it, XSD.integer) },
    )

    private fun objects(graph: MutableRdfGraph, predicate: Iri) = graph.find(s, predicate).map { it.obj }.toSet()

    private fun firstTitle(graph: MutableRdfGraph) =
        graph.find(s, title).firstNotNullOfOrNull { (it.obj as? Literal)?.let(XsdLiterals::string) }

    @Test
    fun `an unchanged single-valued field keeps every term, also those its reader did not return`() {
        val graph = MemoryGraph(listOf(RdfTriple(s, title, LangString("Chat", "fr")), RdfTriple(s, title, LangString("Cat", "en"))))
        assertEquals("Chat", firstTitle(graph), "the reader returns the first representable term")
        graph.mergeRecord(s, listOf(type), listOf(strings(listOf("Chat"), single = true)))
        assertEquals(setOf<RdfTerm>(LangString("Chat", "fr"), LangString("Cat", "en")), objects(graph, title))
        assertEquals(setOf<RdfTerm>(type), objects(graph, RDF.type))
    }

    @Test
    fun `a single-valued update to a value the graph already holds beyond sh maxCount is written`() {
        val graph = MemoryGraph(listOf(RdfTriple(s, title, LangString("Chat", "fr")), RdfTriple(s, title, LangString("Cat", "en"))))
        graph.mergeRecord(s, emptyList(), listOf(strings(listOf("Cat"), single = true)))
        assertEquals("Cat", firstTitle(graph), "the update is visible to the next read")
        assertEquals(setOf<RdfTerm>(LangString("Cat", "en")), objects(graph, title), "the matching term is kept as it is")
    }

    @Test
    fun `a single-valued update removes duplicate terms of the new value and the old value`() {
        val graph = MemoryGraph(
            listOf(
                RdfTriple(s, title, Literal("old")),
                RdfTriple(s, title, LangString("new", "en")),
                RdfTriple(s, title, Literal("new")),
            ),
        )
        graph.mergeRecord(s, emptyList(), listOf(strings(listOf("new"), single = true)))
        assertEquals("new", firstTitle(graph))
        assertEquals(1, objects(graph, title).size, "one value is left for a single-valued member")
    }

    @Test
    fun `a changed field replaces its representable terms and keeps the others`() {
        val illTyped = TypedLiteral("many", XSD.integer)
        val graph = MemoryGraph(listOf(RdfTriple(s, count, TypedLiteral("01", XSD.integer)), RdfTriple(s, count, illTyped)))
        graph.mergeRecord(s, emptyList(), listOf(integers(listOf(BigInteger.TWO))))
        assertEquals(setOf<RdfTerm>(illTyped, TypedLiteral("2", XSD.integer)), objects(graph, count))

        graph.mergeRecord(s, emptyList(), listOf(integers(emptyList())))
        assertEquals(setOf<RdfTerm>(illTyped), objects(graph, count), "clearing removes only representable terms")
    }

    @Test
    fun `a list field keeps the terms of retained values, removes dropped ones and adds new ones`() {
        val graph = MemoryGraph(listOf(RdfTriple(s, title, LangString("a", "en")), RdfTriple(s, title, Literal("b"))))
        graph.mergeRecord(s, emptyList(), listOf(strings(listOf("a", "c"), single = false)))
        assertEquals(setOf<RdfTerm>(LangString("a", "en"), Literal("c")), objects(graph, title))
    }

    @Test
    fun `a failing encode, duplicate predicates and rdf type fields leave the graph unchanged`() {
        val original = listOf(RdfTriple(s, title, Literal("old")), RdfTriple(s, count, TypedLiteral("1", XSD.integer)))
        val graph = MemoryGraph(original)
        val failing = RecordField(count, listOf(BigInteger.ZERO), true, { (it as? Literal)?.let(XsdLiterals::bigInteger) }) {
            XsdLiterals.encode(it, XSD.positiveInteger)
        }
        assertThrows<IllegalArgumentException> { graph.mergeRecord(s, listOf(type), listOf(strings(listOf("new"), true), failing)) }
        assertThrows<IllegalArgumentException> { graph.mergeRecord(s, listOf(type), listOf(strings(listOf("x"), true), strings(listOf("y"), true))) }
        assertThrows<IllegalArgumentException> {
            graph.mergeRecord(s, emptyList(), listOf(RecordField(RDF.type, listOf(type), false, { it as? Iri }, { it })))
        }
        assertThrows<IllegalArgumentException> { strings(listOf("a", "b"), single = true) }
        assertEquals(original.toSet(), graph.getTriples().toSet())
    }

    @Test
    fun `graphs without atomic support are rejected before any write`() {
        val backing = MemoryGraph(listOf(RdfTriple(s, title, Literal("old"))))
        val graph = object : MutableRdfGraph by backing {}
        assertThrows<UnsupportedOperationException> { graph.mergeRecord(s, listOf(type), listOf(strings(listOf("new"), true))) }
        assertEquals(setOf<RdfTerm>(Literal("old")), objects(backing, title))
    }

    private val amount = Iri("urn:amount")

    private fun decimals(values: List<BigDecimal>, single: Boolean) = RecordField(
        amount, values, single, { (it as? Literal)?.let(XsdLiterals::bigDecimal) }, { XsdLiterals.encode(it, XSD.decimal) },
    )

    @Test
    fun `a decimal held with a negative scale is kept, not deleted (round 13 P1)`() {
        // BigDecimal("1000").stripTrailingZeros() is 1E+3: equal in value to the graph's "1000", not in scale.
        for (single in listOf(true, false)) {
            val graph = MemoryGraph(listOf(RdfTriple(s, amount, TypedLiteral("1000", XSD.decimal))))
            graph.mergeRecord(s, emptyList(), listOf(decimals(listOf(BigDecimal("1000").stripTrailingZeros()), single)))
            assertEquals(setOf<RdfTerm>(TypedLiteral("1000", XSD.decimal)), objects(graph, amount), "single=$single")
        }
    }

    @Test
    fun `a decimal equal in value keeps its original term, whatever the scale`() {
        val graph = MemoryGraph(listOf(RdfTriple(s, amount, TypedLiteral("1.00", XSD.decimal)), RdfTriple(s, amount, TypedLiteral("2", XSD.decimal))))
        graph.mergeRecord(s, emptyList(), listOf(decimals(listOf(BigDecimal("1.0"), BigDecimal("2E+0"), BigDecimal("3E+1")), single = false)))
        assertEquals(
            setOf<RdfTerm>(TypedLiteral("1.00", XSD.decimal), TypedLiteral("2", XSD.decimal), TypedLiteral("30", XSD.decimal)),
            objects(graph, amount),
            "unchanged values keep their lexical form; a new value is written in the xsd:decimal syntax, never 3E+1",
        )
    }

    @Test
    fun `a value whose encoded term the graph holds but must drop is re-added`() {
        // The record keeps 1E+3 and drops 7: the add of "1000" must be checked against the terms that remain.
        val graph = MemoryGraph(listOf(RdfTriple(s, amount, TypedLiteral("1000", XSD.decimal)), RdfTriple(s, amount, TypedLiteral("7", XSD.decimal))))
        graph.mergeRecord(s, emptyList(), listOf(decimals(listOf(BigDecimal("1E+3")), single = false)))
        assertEquals(setOf<RdfTerm>(TypedLiteral("1000", XSD.decimal)), objects(graph, amount))
    }
}
