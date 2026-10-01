package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.string
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * SPARQL `UPDATE` follows the same dataset contract on both providers: outside `GRAPH`, the `WHERE` clause matches the
 * default graph only and the `DELETE` / `INSERT` templates change the default graph only, unless `WITH`, `USING` or
 * `USING NAMED` say otherwise. The Jena provider (a plain, non-union dataset) is the reference: every expectation
 * below is asserted on Jena first, and then on RDF4J, whose own default is to match and delete in every context.
 */
class Rdf4jUpdateDatasetParityTest {
    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
    )
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")

    private fun populated(factory: () -> RdfRepository): RdfRepository = factory().also { repo ->
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        repo.editGraph(Iri("urn:g1")).addTriple(RdfTriple(s, p, string("named1")))
        repo.editGraph(Iri("urn:g2")).addTriple(RdfTriple(s, p, string("named2")))
    }

    /** `predicate=literal` for every triple with a literal object (an inferencing store also holds entailed triples). */
    private fun RdfGraph.rendered(): List<String> = getTriples()
        .mapNotNull { triple -> (triple.obj as? Literal)?.let { "${triple.predicate.value.removePrefix("urn:")}=${it.lexical}" } }
        .sorted()

    /** The content of the store: graph name (`default` for the default graph) to its rendered triples; empty graphs are left out. */
    private fun RdfRepository.state(): Map<String, List<String>> {
        val out = sortedMapOf<String, List<String>>()
        defaultGraph.rendered().takeIf { it.isNotEmpty() }?.let { out["default"] = it }
        listGraphs().forEach { name -> getGraph(name).rendered().takeIf { it.isNotEmpty() }?.let { out[name.value.removePrefix("urn:")] = it } }
        return out
    }

    private val initial = mapOf("default" to listOf("p=default"), "g1" to listOf("p=named1"), "g2" to listOf("p=named2"))

    /** The initial state with the given graphs replaced (an empty list removes the graph). */
    private fun changed(vararg graphs: Pair<String, List<String>>): Map<String, List<String>> =
        (initial + graphs).filterValues { it.isNotEmpty() }.toSortedMap()

    private class Case(val name: String, val update: String, val expected: Map<String, List<String>>)

    private val cases = listOf(
        Case(
            "DELETE ... WHERE matches and deletes in the default graph only",
            "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }",
            changed("default" to emptyList()),
        ),
        Case(
            "INSERT ... WHERE matches and inserts in the default graph only",
            "INSERT { ?s <urn:q> ?o } WHERE { ?s ?p ?o }",
            changed("default" to listOf("p=default", "q=default")),
        ),
        Case(
            "DELETE WHERE matches the default graph only",
            "DELETE WHERE { ?s ?p ?o }",
            changed("default" to emptyList()),
        ),
        Case(
            "DELETE WHERE does not reach a triple of a named graph",
            "DELETE WHERE { <urn:s> <urn:p> \"named1\" }",
            changed(),
        ),
        Case(
            "DELETE / INSERT ... WHERE rewrites the default graph only",
            "DELETE { ?s ?p ?o } INSERT { ?s <urn:q> ?o } WHERE { ?s ?p ?o }",
            changed("default" to listOf("q=default")),
        ),
        Case(
            "WITH makes its graph the default graph of the templates and of WHERE",
            "WITH <urn:g1> DELETE { ?s ?p ?o } INSERT { ?s <urn:q> ?o } WHERE { ?s ?p ?o }",
            changed("g1" to listOf("q=named1")),
        ),
        Case(
            "WITH ... DELETE deletes from its graph only",
            "WITH <urn:g1> DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }",
            changed("g1" to emptyList()),
        ),
        Case(
            "WITH still lets WHERE read another graph through GRAPH",
            "WITH <urn:g1> INSERT { ?s <urn:q> ?o } WHERE { GRAPH <urn:g2> { ?s ?p ?o } }",
            changed("g1" to listOf("p=named1", "q=named2")),
        ),
        Case(
            "WITH moves triples to a graph named in the template",
            "WITH <urn:g1> DELETE { ?s ?p ?o } INSERT { GRAPH <urn:g3> { ?s ?p ?o } } WHERE { ?s ?p ?o }",
            changed("g1" to emptyList(), "g3" to listOf("p=named1")),
        ),
        Case(
            "USING sets the default graph of WHERE, the template still writes the default graph",
            "INSERT { ?s <urn:q> ?o } USING <urn:g1> WHERE { ?s ?p ?o }",
            changed("default" to listOf("p=default", "q=named1")),
        ),
        Case(
            "USING does not redirect the DELETE template",
            "DELETE { ?s ?p ?o } USING <urn:g1> WHERE { ?s ?p ?o }",
            changed(),
        ),
        Case(
            "USING hides the named graphs from GRAPH",
            "INSERT { ?s <urn:q> ?o } USING <urn:g1> WHERE { GRAPH ?g { ?s ?p ?o } }",
            changed(),
        ),
        Case(
            "USING NAMED alone leaves WHERE an empty default graph",
            "INSERT { ?s <urn:q> ?o } USING NAMED <urn:g1> WHERE { ?s ?p ?o }",
            changed(),
        ),
        Case(
            "USING NAMED selects the graphs GRAPH can read",
            "INSERT { ?s <urn:q> ?o } USING NAMED <urn:g1> WHERE { GRAPH ?g { ?s ?p ?o } }",
            changed("default" to listOf("p=default", "q=named1")),
        ),
        Case(
            "USING and USING NAMED together",
            "INSERT { ?s <urn:q> ?o } USING <urn:g1> USING NAMED <urn:g2> WHERE { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }",
            changed("default" to listOf("p=default", "q=named1", "q=named2")),
        ),
        Case(
            "WITH and USING: USING feeds WHERE, WITH receives the template",
            "WITH <urn:g1> INSERT { ?s <urn:q> ?o } USING <urn:g2> WHERE { ?s ?p ?o }",
            changed("g1" to listOf("p=named1", "q=named2")),
        ),
        Case(
            "GRAPH in WHERE reads every named graph, the template writes the default graph",
            "INSERT { ?s <urn:q> ?o } WHERE { GRAPH ?g { ?s ?p ?o } }",
            changed("default" to listOf("p=default", "q=named1", "q=named2")),
        ),
        Case(
            "GRAPH in the DELETE template deletes from the matched graphs",
            "DELETE { GRAPH ?g { ?s ?p ?o } } WHERE { GRAPH ?g { ?s ?p ?o } }",
            changed("g1" to emptyList(), "g2" to emptyList()),
        ),
        Case(
            "a DELETE template without GRAPH never deletes from the graph WHERE matched in",
            "DELETE { ?s ?p ?o } WHERE { GRAPH <urn:g1> { ?s ?p ?o } }",
            changed(),
        ),
        Case(
            "GRAPH in the INSERT template copies the default graph",
            "INSERT { GRAPH <urn:g3> { ?s ?p ?o } } WHERE { ?s ?p ?o }",
            changed("g3" to listOf("p=default")),
        ),
        Case(
            "WHERE that reads inside and outside GRAPH",
            "INSERT { GRAPH ?g { ?s <urn:q> ?d } } WHERE { ?s ?p ?d GRAPH ?g { ?s ?p ?o } }",
            changed("g1" to listOf("p=named1", "q=default"), "g2" to listOf("p=named2", "q=default")),
        ),
        Case(
            "NOT EXISTS over GRAPH keeps the default graph apart from the named graphs",
            "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o FILTER NOT EXISTS { GRAPH ?g { ?s ?p ?o } } }",
            changed("default" to emptyList()),
        ),
        Case(
            "DELETE DATA without GRAPH only touches the default graph",
            "DELETE DATA { <urn:s> <urn:p> \"named1\" }",
            changed(),
        ),
        Case(
            "DELETE DATA without GRAPH deletes from the default graph",
            "DELETE DATA { <urn:s> <urn:p> \"default\" }",
            changed("default" to emptyList()),
        ),
        Case(
            "DELETE DATA with GRAPH",
            "DELETE DATA { GRAPH <urn:g1> { <urn:s> <urn:p> \"named1\" } }",
            changed("g1" to emptyList()),
        ),
        Case(
            "INSERT DATA without GRAPH writes the default graph",
            "INSERT DATA { <urn:s> <urn:q> \"x\" }",
            changed("default" to listOf("p=default", "q=x")),
        ),
        Case(
            "INSERT DATA with GRAPH",
            "INSERT DATA { GRAPH <urn:g3> { <urn:s> <urn:q> \"x\" } }",
            changed("g3" to listOf("q=x")),
        ),
        Case(
            "a later operation sees the named graph an earlier one created",
            "INSERT DATA { GRAPH <urn:g3> { <urn:s> <urn:p> \"named3\" } } ; " +
                "INSERT { ?s <urn:q> ?o } WHERE { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }",
            changed("default" to listOf("p=default", "q=named1", "q=named2", "q=named3"), "g3" to listOf("p=named3")),
        ),
        Case(
            "each operation of a request has its own dataset",
            "WITH <urn:g1> DELETE { ?s ?p ?o } WHERE { ?s ?p ?o } ; " +
                "INSERT { ?s <urn:q> ?o } USING <urn:g2> WHERE { ?s ?p ?o } ; " +
                "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o FILTER(?p = <urn:p>) }",
            changed("default" to listOf("q=named2"), "g1" to emptyList()),
        ),
        Case("CLEAR DEFAULT", "CLEAR DEFAULT", changed("default" to emptyList())),
        Case("CLEAR NAMED", "CLEAR NAMED", changed("g1" to emptyList(), "g2" to emptyList())),
        Case("DROP GRAPH", "DROP GRAPH <urn:g1>", changed("g1" to emptyList())),
        Case("COPY DEFAULT TO a graph", "COPY DEFAULT TO <urn:g1>", changed("g1" to listOf("p=default"))),
        Case("ADD a graph TO DEFAULT", "ADD <urn:g1> TO DEFAULT", changed("default" to listOf("p=default", "p=named1"))),
        Case("MOVE a graph", "MOVE <urn:g1> TO <urn:g3>", changed("g1" to emptyList(), "g3" to listOf("p=named1"))),
    )

    @TestFactory
    fun `updates follow the dataset contract of the Jena provider`(): List<DynamicTest> = cases.flatMap { case ->
        providers.map { (provider, factory) ->
            DynamicTest.dynamicTest("$provider: ${case.name}") {
                populated(factory).use { repo ->
                    assertEquals(initial.toSortedMap(), repo.state())
                    repo.update(UpdateQuery(case.update))
                    assertEquals(case.expected, repo.state(), case.update)
                }
            }
        }
    }

    @TestFactory
    fun `an update inside a transaction sees uncommitted named graphs`(): List<DynamicTest> = providers.map { (provider, factory) ->
        DynamicTest.dynamicTest(provider) {
            populated(factory).use { repo ->
                repo.transaction {
                    editGraph(Iri("urn:g3")).addTriple(RdfTriple(s, p, string("named3")))
                    update(UpdateQuery("INSERT { ?s <urn:q> ?o } WHERE { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }"))
                    update(UpdateQuery("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o FILTER(?p = <urn:p>) }"))
                }
                assertEquals(
                    changed("default" to listOf("q=named1", "q=named2", "q=named3"), "g3" to listOf("p=named3")),
                    repo.state(),
                )
            }
        }
    }

    @Test
    fun `an inferencing store matches update WHERE clauses in the default graph only`() {
        populated { Rdf4jRepository.MemoryRdfsRepository() }.use { repo ->
            repo.update(UpdateQuery("INSERT { GRAPH <urn:copy> { ?s ?p ?o } } WHERE { ?s ?p ?o FILTER(isLiteral(?o)) }"))
            assertEquals(listOf("p=default"), repo.getGraph(Iri("urn:copy")).rendered())
            repo.update(UpdateQuery("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o FILTER(isLiteral(?o)) }"))
            assertEquals(emptyList(), repo.defaultGraph.rendered())
            assertEquals(listOf("p=named1"), repo.getGraph(Iri("urn:g1")).rendered())
        }
    }

    @Test
    fun `an update that only reads inside GRAPH still sees blank-node contexts`() {
        val vf = SimpleValueFactory.getInstance()
        val store = SailRepository(MemoryStore()).apply {
            init()
            connection.use { conn ->
                conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createLiteral("default"))
                conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createLiteral("blank"), vf.createBNode("ctx"))
            }
        }
        Rdf4jRepository(store).use { repo ->
            repo.update(UpdateQuery("INSERT { GRAPH <urn:copy> { ?s ?p ?o } } WHERE { GRAPH ?g { ?s ?p ?o } }"))
            assertEquals(listOf("p=blank"), repo.getGraph(Iri("urn:copy")).rendered())
            // Outside GRAPH, the statements of a blank-node context are not in the default graph.
            repo.update(UpdateQuery("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }"))
            assertEquals(emptyList(), repo.defaultGraph.rendered())
            assertEquals(listOf("p=blank"), repo.getGraph(Iri("urn:copy")).rendered())
            store.connection.use { conn -> assertEquals(1, conn.size(vf.createBNode("ctx"))) }
        }
    }
}
