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

    @Test
    fun `an unchanged single-valued field keeps every term, also those its reader did not return`() {
        val graph = MemoryGraph(listOf(RdfTriple(s, title, LangString("Chat", "fr")), RdfTriple(s, title, LangString("Cat", "en"))))
        graph.mergeRecord(s, listOf(type), listOf(strings(listOf("Cat"), single = true)))
        assertEquals(setOf<RdfTerm>(LangString("Chat", "fr"), LangString("Cat", "en")), objects(graph, title))
        assertEquals(setOf<RdfTerm>(type), objects(graph, RDF.type))
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
}
