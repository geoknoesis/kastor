package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DatasetBlankNodeScopeTest {
    private val p = Iri("urn:p")
    private val a = Iri("urn:a")
    private val b = Iri("urn:b")
    private val name = Iri("urn:graph")
    private val node = BlankNode("b0")

    @Test
    fun `independent sources keep colliding labels distinct in views and queries`() {
        MemoryRepository(RdfConfig()).use { left ->
            MemoryRepository(RdfConfig()).use { right ->
                left.editDefaultGraph().addTriple(RdfTriple(node, p, a))
                right.editDefaultGraph().addTriple(RdfTriple(node, p, b))
                val dataset = Dataset { defaultGraph(left); defaultGraph(right) }
                assertEquals(2, dataset.defaultGraph.getTriples().map { it.subject }.distinct().size)
                assertFalse(dataset.ask(SparqlAskQuery("ASK { ?s <urn:p> <urn:a> ; <urn:p> <urn:b> }")))
                assertEquals(2, dataset.select(SparqlSelectQuery("SELECT DISTINCT ?s WHERE { ?s ?p ?o }")).count())
                val subject = dataset.defaultGraph.getTriples().first().subject
                assertEquals(listOf(a), dataset.defaultGraph.find(subject, p).map { it.obj })
                assertTrue(dataset.defaultGraph.hasTriple(RdfTriple(subject, p, a)))
            }
        }
    }

    @Test
    fun `named graphs share a namespace with their own source and never another source`() {
        MemoryRepository(RdfConfig()).use { left ->
            MemoryRepository(RdfConfig()).use { right ->
                left.editDefaultGraph().addTriple(RdfTriple(node, p, a))
                left.editGraph(name).addTriple(RdfTriple(node, p, b))
                right.editDefaultGraph().addTriple(RdfTriple(node, p, b))
                val dataset = Dataset {
                    defaultGraph(left)
                    namedGraph(name, left, name)
                    namedGraph(Iri("urn:other"), right)
                }
                val subject = dataset.defaultGraph.getTriples().single().subject
                assertEquals(subject, dataset.getNamedGraph(name)!!.getTriples().single().subject)
                assertNotEquals(subject, dataset.getNamedGraph(Iri("urn:other"))!!.getTriples().single().subject)
                assertTrue(dataset.ask(SparqlAskQuery("ASK { ?s <urn:p> <urn:a> . GRAPH <urn:graph> { ?s <urn:p> <urn:b> } }")))
                assertFalse(dataset.ask(SparqlAskQuery("ASK { ?s <urn:p> <urn:a> . GRAPH <urn:other> { ?s <urn:p> <urn:b> } }")))
            }
        }
    }

    @Test
    fun `repeated graph references preserve identity and nested triple terms use the same namespace`() {
        val nested = TripleTerm(RdfTriple(node, p, TripleTerm(RdfTriple(a, p, node))))
        val left = MemoryGraph(listOf(RdfTriple(node, p, nested)))
        val right = MemoryGraph(listOf(RdfTriple(node, p, nested)))
        val dataset = Dataset { defaultGraph(left); defaultGraph(left); defaultGraph(right) }
        val rows = dataset.defaultGraph.getTriples()
        assertEquals(2, rows.size)
        rows.forEach { row ->
            val outer = (row.obj as TripleTerm).triple
            assertEquals(row.subject, outer.subject)
            assertEquals(row.subject, (outer.obj as TripleTerm).triple.obj)
            assertTrue(dataset.defaultGraph.hasTriple(row))
            assertEquals(listOf(row), dataset.defaultGraph.find(row.subject, p, row.obj))
        }
        assertEquals(rows, dataset.defaultGraph.getTriples())
        assertEquals(rows.map { it.subject }.toSet(), dataset.select(SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }")).map { it.get("s") }.toSet())
    }

    @Test
    fun `raw handles automatically retain one repository namespace`() {
        MemoryRepository(RdfConfig()).use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(node, p, a))
            repo.editGraph(name).addTriple(RdfTriple(node, p, b))
            val dataset = Dataset { defaultGraph(repo.defaultGraph); namedGraph(name, repo.getGraph(name)) }
            assertTrue(dataset.ask(SparqlAskQuery("ASK { ?s <urn:p> <urn:a> . GRAPH <urn:graph> { ?s <urn:p> <urn:b> } }")))
        }
    }

    @Test
    fun `scoping never deduplicates independent identical blank node triples`() {
        val triple = RdfTriple(node, p, a)
        val dataset = Dataset {
            defaultGraph(MemoryGraph(listOf(triple)))
            defaultGraph(MemoryGraph(listOf(triple)))
        }
        assertEquals(2, dataset.defaultGraph.size())
        assertEquals(2, dataset.select(SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }")).count())
    }

    @Test
    fun `arbitrary UTF-16 blank node ids remain distinct and can be looked up`() {
        val ids = listOf("?", "\uD800", "\uD801", "\uDC00", "\uD83D\uDE00", "\uD83D\uDE01")
        val left = MemoryGraph(ids.map { RdfTriple(BlankNode(it), p, a) })
        val dataset = Dataset { defaultGraph(left); defaultGraph(MemoryGraph()) }
        val rows = dataset.defaultGraph.getTriples()
        assertEquals(ids.size, rows.map { it.subject }.distinct().size)
        rows.forEach { row ->
            assertTrue(dataset.defaultGraph.hasTriple(row))
            assertEquals(listOf(row), dataset.defaultGraph.find(row.subject, p))
        }
        assertEquals(ids.size, dataset.select(SparqlSelectQuery("SELECT DISTINCT ?s WHERE { ?s ?p ?o }")).count())
    }
}
