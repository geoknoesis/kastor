package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The default graph of a SPARQL query is the repository's default graph only - never the union of the named graphs -
 * on every provider, both for plain repository queries and for queries through a [Dataset]. Jena is the reference;
 * RDF4J's own default (no dataset = union of all contexts) must not leak through.
 */
class CrossProviderDatasetSemanticsTest {
    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
        "rdf4j-rdfs" to { Rdf4jRepository.MemoryRdfsRepository() },
    )
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")
    private val g2 = Iri("urn:g2")

    private fun populated(factory: () -> RdfRepository): RdfRepository = factory().also { repo ->
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        repo.editGraph(g1).addTriple(RdfTriple(s, p, string("named1")))
        repo.editGraph(g2).addTriple(RdfTriple(s, p, string("named2")))
    }

    private fun SparqlQueryResult.strings(name: String): List<String> =
        mapNotNull { (it.get(name) as? Literal)?.lexical }.sorted()

    /** Lexical forms of the literal objects (an inferencing store also returns entailed triples, which have none). */
    private fun Sequence<RdfTriple>.objects(): List<String> = mapNotNull { (it.obj as? Literal)?.lexical }.sorted().toList()

    private fun each(what: String, body: (RdfRepository) -> Unit): List<DynamicTest> = providers.map { (name, factory) ->
        DynamicTest.dynamicTest("$name: $what") { populated(factory).use(body) }
    }

    @TestFactory
    fun `plain repository queries read only the default graph outside GRAPH`() = each("plain repository, no GRAPH") { repo ->
        assertEquals(listOf("default"), repo.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o }")).strings("o"))
        assertEquals(listOf("default"), repo.select(SparqlSelectQuery("SELECT ?o { <urn:s> <urn:p>+ ?o }")).strings("o"))
        assertEquals(
            1,
            repo.select(SparqlSelectQuery("SELECT (COUNT(*) AS ?n) { ?s ?p ?o FILTER(isLiteral(?o)) }")).toList().single().getInt("n"),
        )
        assertFalse(repo.ask(SparqlAskQuery("ASK { ?s ?p \"named1\" }")))
        assertTrue(repo.ask(SparqlAskQuery("ASK { ?s ?p \"default\" }")))
        assertEquals(listOf("default"), repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).objects())
        assertEquals(listOf("default"), repo.construct(SparqlConstructQuery("CONSTRUCT WHERE { ?s ?p ?o }")).objects())
        // DESCRIBE is implementation-defined: Jena also describes the resource in every named graph.
        assertTrue("default" in repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).objects())
    }

    @TestFactory
    fun `plain repository queries read every named graph inside GRAPH`() = each("plain repository, GRAPH") { repo ->
        assertEquals(
            listOf("named1", "named2"),
            repo.select(SparqlSelectQuery("SELECT ?o { GRAPH ?g { ?s ?p ?o } }")).strings("o"),
        )
        assertEquals(
            listOf("urn:g1", "urn:g2"),
            repo.select(SparqlSelectQuery("SELECT DISTINCT ?g { GRAPH ?g { ?s ?p ?o } }")).map { (it.get("g") as Iri).value }.sorted(),
        )
        assertEquals(listOf("named1"), repo.select(SparqlSelectQuery("SELECT ?o { GRAPH <urn:g1> { ?s ?p ?o } }")).strings("o"))
        assertEquals(
            listOf("default", "named2"),
            repo.select(SparqlSelectQuery("SELECT ?o { { ?s ?p ?o } UNION { GRAPH <urn:g2> { ?s ?p ?o } } }")).strings("o"),
        )
        assertTrue(repo.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p \"named1\" } }")))
        assertFalse(repo.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p \"default\" } }")))
        assertFalse(repo.ask(SparqlAskQuery("ASK { GRAPH <urn:absent> { ?s ?p ?o } }")))
        assertEquals(
            listOf("named1", "named2"),
            repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }")).objects(),
        )
    }

    @TestFactory
    fun `a query's own dataset clauses still select its graphs`() = each("plain repository, FROM / FROM NAMED") { repo ->
        assertEquals(listOf("named1"), repo.select(SparqlSelectQuery("SELECT ?o FROM <urn:g1> { ?s ?p ?o }")).strings("o"))
        assertEquals(
            listOf("named1", "named2"),
            repo.select(SparqlSelectQuery("SELECT ?o FROM <urn:g1> FROM <urn:g2> { ?s ?p ?o }")).strings("o"),
        )
        assertEquals(
            listOf("named2"),
            repo.select(SparqlSelectQuery("SELECT ?o FROM NAMED <urn:g2> { GRAPH ?g { ?s ?p ?o } }")).strings("o"),
        )
        assertEquals(emptyList(), repo.select(SparqlSelectQuery("SELECT ?o FROM NAMED <urn:g2> { ?s ?p ?o }")).strings("o"))
    }

    @TestFactory
    fun `a dataset of the repository's default graph has no named graphs`() = each("Dataset { defaultGraph(repo) }") { repo ->
        val dataset = Dataset { defaultGraph(repo) }
        assertEquals(listOf("default"), dataset.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o }")).strings("o"))
        assertFalse(dataset.ask(SparqlAskQuery("ASK { ?s ?p \"named1\" }")))
        assertTrue(dataset.ask(SparqlAskQuery("ASK { ?s ?p \"default\" }")))
        assertEquals(
            listOf("default"),
            dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).objects(),
        )
        assertEquals(emptyList(), dataset.select(SparqlSelectQuery("SELECT ?o { GRAPH ?g { ?s ?p ?o } }")).strings("o"))
        assertEquals(emptyList(), dataset.select(SparqlSelectQuery("SELECT ?o{{?s ?p ?o}GRAPH ?h{?s ?p ?x}}")).strings("o"))
        assertFalse(dataset.ask(SparqlAskQuery("ASK { GRAPH <urn:g1> { ?s ?p ?o } }")))
        assertEquals(
            emptyList(),
            dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }")).objects(),
        )
        assertEquals(
            listOf("default"),
            dataset.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o OPTIONAL { GRAPH ?g { ?s ?p ?x } } }")).strings("o"),
        )
        assertEquals(listOf("default"), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).objects().distinct())
        assertEquals(listOf("default"), dataset.describe(SparqlDescribeQuery("DESCRIBE ?s { ?s ?p ?o }")).objects().distinct())
        assertEquals(
            emptyList(),
            dataset.describe(SparqlDescribeQuery("DESCRIBE ?s { GRAPH ?g { ?s ?p ?o } }")).objects().distinct(),
        )
    }

    @TestFactory
    fun `a dataset of named graphs of one repository sees exactly those graphs`() = each("Dataset of named graphs") { repo ->
        val dataset = Dataset {
            defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
            namedGraph(g2, repo, g2)
        }
        assertEquals(listOf("named1"), dataset.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o }")).strings("o"))
        assertEquals(listOf("named2"), dataset.select(SparqlSelectQuery("SELECT ?o { GRAPH ?g { ?s ?p ?o } }")).strings("o"))
        assertFalse(dataset.ask(SparqlAskQuery("ASK { GRAPH <urn:g1> { ?s ?p ?o } }")))
        assertFalse(dataset.ask(SparqlAskQuery("ASK { ?s ?p \"default\" }")))
        // DESCRIBE never leaves the dataset; whether it also reads the dataset's named graphs is up to the engine
        // (Jena does, RDF4J does not).
        val described = dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).objects().distinct()
        assertTrue("named1" in described && "default" !in described, described.toString())
        assertEquals(if (repo is JenaRepository) listOf("named1", "named2") else listOf("named1"), described)
    }

    @TestFactory
    fun `scoped and bound queries read only the default graph outside GRAPH`() = each("withSelectRows / withConstructTriples") { repo ->
        val select = SparqlSelectQuery("SELECT ?o { ?s ?p ?o FILTER(isLiteral(?o)) }")
        assertEquals(listOf("default"), repo.withSelectRows(select) { rows -> rows.map { it.getString("o")!! }.toList() })
        assertEquals(
            listOf("default"),
            repo.withSelectRows(select, mapOf("s" to s), java.time.Duration.ofSeconds(30)) { rows -> rows.map { it.getString("o")!! }.toList() },
        )
        val construct = SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
        assertEquals(listOf("default"), repo.withConstructTriples(construct) { it.objects() })
        val mixed = SparqlSelectQuery("SELECT ?o { ?s ?p ?d GRAPH ?g { ?s ?p ?o } }")
        assertEquals(listOf("named1", "named2"), repo.withSelectRows(mixed) { rows -> rows.mapNotNull { it.getString("o") }.toList().sorted() })
        // Uncommitted named graphs are named graphs of a query inside the same transaction.
        repo.transaction {
            editGraph(Iri("urn:g3")).addTriple(RdfTriple(s, p, string("named3")))
            assertEquals(listOf("named1", "named2", "named3"), select(mixed).strings("o"))
            assertEquals(listOf("default"), select(select).strings("o"))
        }
    }

    @TestFactory
    fun `describe through a default-graph dataset keeps blank node closures of the default graph`() =
        each("Dataset { defaultGraph(repo) } DESCRIBE with blank nodes") { repo ->
            val b = BlankNode("b1")
            repo.editDefaultGraph().addTriples(listOf(RdfTriple(s, Iri("urn:q"), b), RdfTriple(b, p, string("nested"))))
            repo.editGraph(g1).addTriple(RdfTriple(s, Iri("urn:q"), Iri("urn:only-named")))
            val described = Dataset { defaultGraph(repo) }.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList()
            assertEquals(listOf("default", "nested"), described.asSequence().objects().distinct())
            assertFalse(described.any { it.obj == Iri("urn:only-named") })
        }

    /**
     * Pins the remaining provider difference (documented on `Rdf4jRepository`): RDF4J matches the `WHERE` clause of
     * an update against all contexts, Jena against the default graph only.
     */
    @TestFactory
    fun `update WHERE clauses differ between providers`() = each("UPDATE ... WHERE") { repo ->
        repo.update(UpdateQuery("INSERT { GRAPH <urn:copy> { ?s ?p ?o } } WHERE { ?s ?p ?o FILTER(isLiteral(?o)) }"))
        val copied = repo.getGraph(Iri("urn:copy")).getTriples().asSequence().objects()
        if (repo is JenaRepository) {
            assertEquals(listOf("default"), copied)
        } else {
            assertEquals(listOf("default", "named1", "named2"), copied)
        }
    }
}
