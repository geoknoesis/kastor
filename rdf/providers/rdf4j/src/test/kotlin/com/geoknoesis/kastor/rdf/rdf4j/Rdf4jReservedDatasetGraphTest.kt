package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Rdf4jReservedDatasetGraphTest {
    private val p = Iri("urn:p")
    private val private = RdfTriple(Iri("urn:private"), p, Iri("urn:secret"))
    private val public = RdfTriple(Iri("urn:visible"), p, Iri("urn:public"))

    @ParameterizedTest
    @ValueSource(strings = ["http://rdf4j.org/schema/rdf4j#nil", "http://www.openrdf.org/schema/sesame#nil"])
    fun `a reserved named graph selected as default cannot expose the source default graph`(name: String) {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graphName = Iri(name)
            repo.editDefaultGraph().addTriple(private)
            repo.editGraph(graphName).addTriple(public)
            // Cover both automatically tracked handles and explicit GraphRef metadata.
            listOf(repo.getGraph(graphName), GraphRef(repo.getGraph(graphName), repo, graphName)).forEach { source ->
                val dataset = Dataset { defaultGraph(source) }
                assertEquals(listOf(public), dataset.defaultGraph.getTriples())
                assertFalse(dataset.ask(SparqlAskQuery("ASK { <urn:private> ?p ?o }")))
                assertTrue(dataset.ask(SparqlAskQuery("ASK { <urn:visible> ?p ?o }")))
                assertEquals(listOf(public.subject), dataset.select(SparqlSelectQuery("SELECT ?s { ?s ?p ?o }")).map { it.get("s") }.toList())
                assertEquals(listOf(public), dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).toList())
                assertEquals(emptyList<RdfTriple>(), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:private>")).toList())
                assertEquals(listOf(public), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:visible>")).toList())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://rdf4j.org/schema/rdf4j#nil", "http://www.openrdf.org/schema/sesame#nil"])
    fun `reserved named graphs remain accessible without adding unrelated graphs`(name: String) {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val normal = Iri("urn:normal")
            val graphName = Iri(name)
            val named = RdfTriple(Iri("urn:named"), p, Iri("urn:named-public"))
            repo.editDefaultGraph().addTriple(private)
            repo.editGraph(normal).addTriple(public)
            repo.editGraph(graphName).addTriple(named)
            repo.editGraph(Iri("urn:hidden")).addTriple(private)
            val dataset = Dataset {
                defaultGraph(repo.getGraph(normal))
                namedGraph(graphName, repo.getGraph(graphName))
            }
            assertEquals(listOf(named), dataset.graph(graphName).getTriples())
            assertTrue(dataset.ask(SparqlAskQuery("ASK { <urn:visible> <urn:p> <urn:public> GRAPH <$name> { <urn:named> <urn:p> <urn:named-public> } }")))
            assertFalse(dataset.ask(SparqlAskQuery("ASK { GRAPH <$name> { <urn:visible> ?p ?o } }")))
            assertFalse(dataset.ask(SparqlAskQuery("ASK { <urn:named> ?p ?o }")))
            assertFalse(dataset.ask(SparqlAskQuery("ASK { GRAPH ?g { <urn:private> ?p ?o } }")))
            assertEquals(listOf(graphName), dataset.select(SparqlSelectQuery("SELECT DISTINCT ?g { GRAPH ?g { ?s ?p ?o } }")).map { it.get("g") }.toList())
        }
    }
}
