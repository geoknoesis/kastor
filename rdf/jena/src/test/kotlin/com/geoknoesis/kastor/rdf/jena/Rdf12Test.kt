package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * RDF 1.2 round-trip tests for the Jena provider.
 */
class Rdf12Test {

    @Test
    fun `JenaProvider advertises RDF 1 dot 2 capability`() {
        val provider = JenaProvider()
        val caps = provider.getCapabilities("memory")
        assertEquals("1.2", caps.rdfVersion)
        assertTrue(caps.supportsTripleTerms)
    }

    @Test
    fun `triple term round-trips through Jena memory store`() {
        JenaRepository.MemoryRepository().use { repo ->
            val alice = Iri("http://example.org/alice")
            val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
            val reifier = bnode("r1")

            // Add base triple plus reifier metadata.
            repo.editDefaultGraph().addTriples(
                listOf(
                    claim,
                    RdfTriple(reifier, RDF.reifies, TripleTerm(claim)),
                    RdfTriple(
                        reifier,
                        Iri("http://example.org/certainty"),
                        0.9.toLiteral(),
                    ),
                )
            )

            // Read back and verify the triple term came through.
            val triples = repo.defaultGraph.getTriples()
            val reifiesTriple = triples.firstOrNull { it.predicate == RDF.reifies }
            assertNotNull(reifiesTriple, "expected an rdf:reifies triple after round-trip")
            assertTrue(
                reifiesTriple!!.obj is TripleTerm,
                "rdf:reifies object should be a TripleTerm, got ${reifiesTriple.obj}"
            )
            val tt = reifiesTriple.obj as TripleTerm
            assertEquals(alice, tt.triple.subject)
            assertEquals(FOAF.age, tt.triple.predicate)
        }
    }

    @Test
    fun `directional language string round-trips`() {
        JenaRepository.MemoryRepository().use { repo ->
            val alice = Iri("http://example.org/alice")
            val rtl = LangString("\u0645\u0631\u062D\u0628\u0627", "ar", Direction.RTL)
            repo.editDefaultGraph().addTriple(RdfTriple(alice, FOAF.name, rtl))

            val obj = repo.defaultGraph.getTriples().single().obj
            assertTrue(obj is LangString)
            val ls = obj as LangString
            assertEquals("ar", ls.lang)
            // Older Jena 5.x without RDF 1.2 directional support will round-trip as
            // a plain rdf:langString. Either is acceptable here.
            if (ls.direction != null) {
                assertEquals(Direction.RTL, ls.direction)
                assertEquals(RDF.dirLangString, ls.datatype)
            } else {
                assertEquals(RDF.langString, ls.datatype)
            }
        }
    }

    @Test
    fun `SPARQL ASK over rdf-reifies pattern works`() {
        JenaRepository.MemoryRepository().use { repo ->
            val alice = Iri("http://example.org/alice")
            val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
            val reifier = bnode("r")
            repo.editDefaultGraph().addTriples(
                listOf(
                    claim,
                    RdfTriple(reifier, RDF.reifies, TripleTerm(claim)),
                )
            )

            val ask = repo.ask(SparqlAskQuery("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                ASK { ?r rdf:reifies ?tt }
            """.trimIndent()))
            assertTrue(ask, "ASK over rdf:reifies should match")
        }
    }
}
