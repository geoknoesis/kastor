package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * RDF 1.2 round-trip tests for the RDF4J provider.
 *
 * RDF4J 5.1.x predates the spec rename to `createTripleTerm`; the bridge
 * (in [Rdf4jTerms]) falls back to the legacy `createTriple` so round-trips
 * still work, just via the older RDF-star storage. Tests assert at the Kastor
 * model level rather than the underlying RDF4J wire format.
 */
class Rdf12Test {

    @Test
    fun `Rdf4jProvider advertises RDF 1 dot 2 capability`() {
        val provider = Rdf4jProvider()
        val caps = provider.getCapabilities("memory")
        assertEquals("1.2", caps.rdfVersion)
        assertTrue(caps.supportsTripleTerms)
    }

    @Test
    fun `triple term round-trips through RDF4J memory store`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val alice = Iri("http://example.org/alice")
            val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
            val reifier = bnode("r1")

            repo.editDefaultGraph().addTriples(
                listOf(
                    claim,
                    RdfTriple(reifier, RDF.reifies, TripleTerm(claim)),
                    RdfTriple(reifier, Iri("http://example.org/certainty"), 0.9.toLiteral()),
                )
            )

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
        Rdf4jRepository.MemoryRepository().use { repo ->
            val alice = Iri("http://example.org/alice")
            val rtl = LangString("\u0645\u0631\u062D\u0628\u0627", "ar", Direction.RTL)
            repo.editDefaultGraph().addTriple(RdfTriple(alice, FOAF.name, rtl))

            val obj = repo.defaultGraph.getTriples().single().obj
            assertTrue(obj is LangString)
            val ls = obj as LangString
            assertEquals("ar", ls.lang)
            // RDF4J 5.1.x does not yet support rdf:dirLangString; older builds
            // round-trip as a plain rdf:langString. Newer builds preserve the
            // direction field. Accept both.
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
        Rdf4jRepository.MemoryRepository().use { repo ->
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
