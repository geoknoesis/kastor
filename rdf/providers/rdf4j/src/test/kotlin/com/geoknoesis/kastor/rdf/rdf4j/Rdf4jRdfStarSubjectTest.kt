package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.SparqlConstructQuery
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RDF4J stores RDF-star statements whose subject is a quoted triple. RDF 1.2 cannot represent them
 * directly, so the adapter reads them as the RDF 1.2 reified form: a deterministic reifier blank node
 * `_:r rdf:reifies <<( s p o )>>` plus `_:r p' o'`. One such statement must never make the graph unreadable.
 * (RDF4J NativeStore cannot store quoted triples at all, so only memory-based stores are exercised.)
 */
class Rdf4jRdfStarSubjectTest {
    private val ex = "http://example.org/"
    private val alice = Iri(ex + "alice")
    private val age = Iri(ex + "age")
    private val certainty = Iri(ex + "certainty")
    private val claim = RdfTriple(alice, age, TypedLiteral("30", XSD.integer))
    private val insert = "PREFIX ex: <$ex> INSERT DATA { ex:alice ex:name \"Alice\" . << ex:alice ex:age 30 >> ex:certainty \"0.9\" }"

    private fun repositories(): List<Pair<String, Rdf4jRepository>> = listOf(
        "memory" to Rdf4jRepository.MemoryRepository(),
        "memory-rdfs" to Rdf4jRepository.MemoryRdfsRepository(),
    )

    private fun assertReified(name: String, triples: Collection<RdfTriple>) {
        val annotation = triples.single { it.predicate == certainty }
        val reifier = annotation.subject
        assertTrue(reifier is BlankNode, "$name: reifier must be a blank node, got $reifier")
        assertEquals(TypedLiteral("0.9", XSD.string), annotation.obj, name)
        assertTrue(RdfTriple(reifier, RDF.reifies, TripleTerm(claim)) in triples, "$name: $triples")
        assertTrue(RdfTriple(alice, Iri(ex + "name"), TypedLiteral("Alice", XSD.string)) in triples, name)
    }

    @Test
    fun `quoted-triple subject statements are read as the RDF 1 dot 2 reified form`() = repositories().forEach { (name, repo) ->
        repo.use {
            repo.update(UpdateQuery(insert))
            val graph = repo.defaultGraph
            val triples = graph.getTriples()
            assertReified(name, triples)
            assertEquals(triples.toSet().size, triples.size, "$name: no duplicates")
            if (name == "memory") assertEquals(3, graph.size(), name)
            assertEquals(graph.getTriples().toSet(), triples.toSet(), "$name: reifier ids are deterministic")

            val reifier = triples.single { it.predicate == certainty }.subject
            assertEquals(1, graph.find(reifier, certainty, null).size, name)
            assertEquals(1, graph.find(null, RDF.reifies, null).size, name)
            assertEquals(1, graph.find(reifier, null, null).count { it.predicate == RDF.reifies }, name)
            assertTrue(graph.hasTriple(RdfTriple(reifier, RDF.reifies, TripleTerm(claim))), name)
            assertEquals(1, graph.find(null, null, TripleTerm(claim)).size, name)
        }
    }

    @Test
    fun `reified form can be removed through the RDF 1 dot 2 view`() = repositories().take(2).forEach { (name, repo) ->
        repo.use {
            repo.update(UpdateQuery(insert))
            val graph = repo.editDefaultGraph()
            val reifier = graph.find(null, certainty, null).single().subject

            // Dropping only rdf:reifies keeps the annotation on a plain blank node.
            assertTrue(graph.removeTriple(RdfTriple(reifier, RDF.reifies, TripleTerm(claim))), name)
            assertTrue(graph.find(null, RDF.reifies, null).isEmpty(), name)
            val remaining = graph.find(null, certainty, null).single()
            assertEquals(reifier, remaining.subject, name)

            assertTrue(graph.removeTriple(remaining), name)
            assertTrue(graph.find(null, certainty, null).isEmpty(), name)
            assertFalse(graph.removeTriple(remaining), name)
        }
    }

    @Test
    fun `annotation removal keeps the reification`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery(insert))
            val graph = repo.editDefaultGraph()
            val annotation = graph.find(null, certainty, null).single()
            assertTrue(graph.removeTriple(annotation))
            assertTrue(graph.find(null, certainty, null).isEmpty())
        }
    }

    @Test
    fun `turtle parse and CONSTRUCT map quoted-triple subjects`() {
        val parsed = Rdf4jProvider().parseGraph(
            "PREFIX ex: <$ex>\nex:alice ex:name \"Alice\" .\n<< ex:alice ex:age 30 >> ex:certainty \"0.9\" .".byteInputStream(),
            "TURTLE",
        ).getTriples()
        assertReified("parse", parsed)
        assertEquals(3, parsed.size)

        val streamed = Rdf4jProvider().parseStreaming(
            "PREFIX ex: <$ex>\n<< ex:alice ex:age 30 >> ex:certainty \"0.9\" ; ex:source ex:census .".byteInputStream(),
            "TURTLE",
        ).toList()
        assertEquals(3, streamed.size, "$streamed")
        assertEquals(1, streamed.count { it.predicate == RDF.reifies })

        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery(insert))
            val constructed = repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).toList()
            assertReified("construct", constructed)
        }
    }
}
