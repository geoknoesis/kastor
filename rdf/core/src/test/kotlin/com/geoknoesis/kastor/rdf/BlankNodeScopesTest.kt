package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlankNodeScopesTest {
    @Test
    fun `preserved labels cannot collide with generated labels including inside quoted triples`() {
        val p = Iri("urn:p")
        val node = BlankNode("b")
        val generated = BlankNode("kastor_scope_0_AGI") // raw UTF-16 encoding of b
        val escaped = BlankNode("_kastor_scope_0_AGI")
        val anchor = MemoryGraph(listOf(RdfTriple(generated, p, TripleTerm(RdfTriple(escaped, p, generated)))))
        val imported = MemoryGraph(listOf(RdfTriple(node, p, node)))
        val scopes = BlankNodeScopes(preservedGraph = anchor)
        assertSame(anchor, scopes.graph(anchor))
        val view = scopes.graph(imported)
        val row = view.getTriples().single()
        assertEquals(BlankNode("__kastor_scope_0_AGI"), row.subject)
        assertEquals(row.subject, row.obj)
        assertEquals(row.subject, scopes.mapTerm(imported, node))
        assertEquals(generated, scopes.mapTerm(anchor, generated))
        assertTrue(view.hasTriple(row))
        assertEquals(listOf(row), view.find(row.subject, p, row.obj))
        assertTrue(view.find(generated, p).isEmpty())
        assertTrue(view.find(escaped, p).isEmpty())
        assertEquals(view.getTriples(), BlankNodeScopes(anchor).graph(imported).getTriples())
    }

    @Test
    fun `preserving one repository graph preserves its shared blank nodes in other graphs`() {
        MemoryRepository(RdfConfig()).use { repo ->
            val row = RdfTriple(BlankNode("shared"), Iri("urn:p"), Iri("urn:o"))
            repo.editDefaultGraph().addTriple(row)
            repo.editGraph(Iri("urn:g")).addTriple(row)
            val scopes = BlankNodeScopes(repo.defaultGraph)
            assertEquals(listOf(row), scopes.graph(repo.getGraph(Iri("urn:g"))).getTriples())
            assertNotEquals(row.subject, scopes.graph(MemoryGraph(listOf(row))).getTriples().single().subject)
        }
    }
}
