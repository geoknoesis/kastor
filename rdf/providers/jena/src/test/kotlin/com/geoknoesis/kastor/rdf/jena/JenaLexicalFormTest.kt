package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

/** Regression: Jena term conversion must never rewrite literal lexical forms. */
class JenaLexicalFormTest {

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")

    @ParameterizedTest
    @CsvSource(
        "007, http://www.w3.org/2001/XMLSchema#integer",
        "1.50, http://www.w3.org/2001/XMLSchema#decimal",
        "1e3, http://www.w3.org/2001/XMLSchema#double",
        "2024-01-01T10:00:00, http://www.w3.org/2001/XMLSchema#dateTime",
        "2024-01-01T10:00:00.000Z, http://www.w3.org/2001/XMLSchema#dateTime",
        "1, http://www.w3.org/2001/XMLSchema#boolean",
        "maybe, http://www.w3.org/2001/XMLSchema#boolean",
    )
    fun `stored literal keeps its lexical form and can be removed`(lexical: String, datatype: String) {
        JenaRepository.MemoryRepository().use { repo ->
            val literal = TypedLiteral(lexical, Iri(datatype))
            val graph = repo.editDefaultGraph()
            graph.addTriple(RdfTriple(s, p, literal))

            val stored = graph.getTriples().single()
            assertEquals(lexical, (stored.obj as Literal).lexical)
            assertEquals(Iri(datatype), (stored.obj as Literal).datatype)
            assertTrue(graph.hasTriple(stored))
            assertEquals(1, graph.find(s, p, stored.obj).size)
            assertTrue(graph.removeTriple(stored), "removeTriple(getTriples().single()) must succeed")
            assertEquals(0, graph.size())
        }
    }

    @Test
    fun `canonical booleans map to singletons`() {
        JenaRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(RdfTriple(s, p, TypedLiteral("true", XSD.boolean)))
            assertSame(TrueLiteral, graph.getTriples().single().obj)
        }
    }

    @Test
    fun `query bindings preserve lexical forms`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, TypedLiteral("1.50", XSD.decimal)))
            val row = repo.select(SparqlSelectQuery("SELECT ?o WHERE { ?s ?p ?o }")).single()
            assertEquals(TypedLiteral("1.50", XSD.decimal), row.get("o"))
        }
    }

    @Test
    fun `directional language string round-trips through raw nodes`() {
        val rtl = LangString("abc", "ar", Direction.RTL)
        assertEquals(rtl, JenaTerms.fromJenaNode(JenaTerms.toJenaNode(rtl)))
    }
}
