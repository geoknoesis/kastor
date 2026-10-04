package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PropertyDelegateContractTest {
    private val subject = Iri("urn:subject")
    private val predicate = Iri("urn:property")
    private fun graph(vararg values: RdfTerm) = MemoryGraph(values.map { RdfTriple(subject, predicate, it) })
    private fun handle(graph: RdfGraph) = DefaultRdfHandle(subject, graph, emptySet())

    @Test
    fun `IRI delegates ignore literals and blank nodes and preserve all IRI values`() {
        val first = Iri("urn:first")
        val second = Iri("urn:second")
        val graph = graph(string("text"), BlankNode("blank"), first, second)
        val view = object : RdfBacked {
            override val rdf = handle(graph)
            val required by rdfIri(predicate)
            val optional by rdfIriOrNull(predicate)
            val all by rdfIris(predicate)
        }
        assertEquals(first, view.required)
        assertEquals(first, view.optional)
        assertEquals(listOf(first, second), view.all)
    }

    @Test
    fun `missing IRI values fail only for required properties and nullable results are memoized`() {
        val graph = graph(BlankNode("blank"), string("text"))
        val view = object : RdfBacked {
            override val rdf = handle(graph)
            val required by rdfIri(predicate)
            val optional by rdfIriOrNull(predicate)
            val all by rdfIris(predicate)
        }
        assertThrows(MaterializationException::class.java) { view.required }
        assertNull(view.optional)
        assertTrue(view.all.isEmpty())
        graph.addTriple(RdfTriple(subject, predicate, Iri("urn:added")))
        assertNull(view.optional, "A computed null is a memoized value")
        assertTrue(view.all.isEmpty(), "A computed empty list is a memoized value")
        assertEquals(Iri("urn:added"), view.required, "A failed initialization may be retried")
    }

    @Test
    fun `language delegates match tags case insensitively and exclude plain literals from language maps`() {
        val graph = graph(string("plain"), LangString("Hello", "en-US"), LangString("Bonjour", "fr"), Iri("urn:object"))
        val view = object : RdfBacked {
            override val rdf = handle(graph)
            val english by rdfLangString(predicate, "EN-us")
            val missing by rdfLangString(predicate, "de")
            val languages by rdfLangStringMap(predicate)
            val lexical by rdfLexicalFirstOrNull(predicate)
        }
        assertEquals("Hello", view.english)
        assertNull(view.missing)
        assertEquals(mapOf("en-US" to "Hello", "fr" to "Bonjour"), view.languages)
        assertEquals("plain", view.lexical)
    }

    @Test
    fun `language and lexical delegates return empty values when there are no literals`() {
        val graph = graph(Iri("urn:object"))
        val view = object : RdfBacked {
            override val rdf = handle(graph)
            val language by rdfLangString(predicate, "en")
            val languages by rdfLangStringMap(predicate)
            val lexical by rdfLexicalFirstOrNull(predicate)
        }
        assertNull(view.language)
        assertTrue(view.languages.isEmpty())
        assertNull(view.lexical)
    }

    interface Child : RdfBacked

    @Test
    fun `object delegates materialize IRIs and blank nodes using the same backing graph`() {
        val first = Iri("urn:child")
        val second = BlankNode("child")
        val graph = graph(string("ignored"), first, second)
        OntoMapper.register(Child::class.java) { handle -> object : Child { override val rdf = handle } }
        try {
            val view = object : RdfBacked {
                override val rdf = handle(graph)
                val required by rdfObject<Child>(predicate)
                val optional by rdfObjectOrNull<Child>(predicate)
                val all by rdfObjects<Child>(predicate)
            }
            assertEquals(first, view.required.rdf.node)
            assertEquals(first, view.optional!!.rdf.node)
            assertEquals(listOf(first, second), view.all.map { it.rdf.node })
            view.all.forEach { assertSame(graph, it.rdf.graph) }
        } finally {
            OntoMapper.unregister(Child::class.java)
        }
    }

    @Test
    fun `missing object properties distinguish required optional and collection values`() {
        val graph = graph(string("not an object"))
        val view = object : RdfBacked {
            override val rdf = handle(graph)
            val required by rdfObject<Child>(predicate)
            val optional by rdfObjectOrNull<Child>(predicate)
            val all by rdfObjects<Child>(predicate)
        }
        assertThrows(MaterializationException::class.java) { view.required }
        assertNull(view.optional)
        assertTrue(view.all.isEmpty())
    }

    @Test
    fun `typed literal delegates reject malformed values and restore the callers policy`() {
        val graph = graph(TypedLiteral("bad", XSD.int), TypedLiteral("7", XSD.int))
        val strict = object : RdfBacked {
            override val rdf = handle(graph)
            val value by rdfLiteral(predicate, XsdLiterals::int)
        }
        val tolerant = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
            object : RdfBacked {
                override val rdf = handle(graph)
                val required by rdfLiteral(predicate, XsdLiterals::int)
                val optional by rdfLiteralOrNull(predicate, XsdLiterals::int)
                val all by rdfLiterals(predicate, XsdLiterals::int)
            }
        }
        val before = MaterializationPolicy.illTypedValues
        assertThrows(MaterializationException::class.java) { strict.value }
        assertEquals(7, tolerant.required)
        assertEquals(7, tolerant.optional)
        assertEquals(listOf(7), tolerant.all)
        assertEquals(before, MaterializationPolicy.illTypedValues)
    }
}
