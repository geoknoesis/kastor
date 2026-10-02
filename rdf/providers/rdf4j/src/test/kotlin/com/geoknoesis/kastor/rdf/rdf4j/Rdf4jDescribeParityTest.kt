package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DESCRIBE` returns the same description on both providers: for every resource the query selects, its outgoing
 * statements and, recursively, the outgoing statements of the blank nodes they lead to (the concise bounded
 * description the Jena provider returns; statements that merely point at the resource are not part of it). The
 * description is read from the default graph of the query's dataset: the store's default graph, or the merge of the
 * `FROM` graphs when the query declares a dataset. Named graphs are only read by the `WHERE` clause (inside `GRAPH`),
 * never by the description itself.
 */
class Rdf4jDescribeParityTest {
    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "jena-inference" to { JenaRepository.MemoryRepositoryWithInference() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
        "rdf4j-rdfs" to { Rdf4jRepository.MemoryRdfsRepository() },
    )
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val q = Iri("urn:q")
    private val knows = Iri("urn:knows")

    private fun populated(factory: () -> RdfRepository): RdfRepository = factory().also { repo ->
        val b1 = BlankNode("b1")
        val b2 = BlankNode("b2")
        val c1 = BlankNode("c1")
        val c2 = BlankNode("c2")
        repo.editDefaultGraph().addTriples(
            listOf(
                RdfTriple(s, p, string("default")),
                // A chain of blank nodes below the resource.
                RdfTriple(s, q, b1),
                RdfTriple(b1, p, string("nested")),
                RdfTriple(b1, q, b2),
                RdfTriple(b2, p, string("deep")),
                // Statements that point at the resource (also from a blank node) are not part of its description.
                RdfTriple(Iri("urn:x"), knows, s),
                RdfTriple(Iri("urn:x"), p, string("incoming")),
                RdfTriple(BlankNode("in"), knows, s),
                RdfTriple(BlankNode("in"), p, string("incoming-blank")),
                // A cycle of blank nodes, one of which points back at the IRI it hangs from.
                RdfTriple(Iri("urn:c"), q, c1),
                RdfTriple(c1, p, string("cycle1")),
                RdfTriple(c1, q, c2),
                RdfTriple(c2, p, string("cycle2")),
                RdfTriple(c2, q, c1),
                RdfTriple(c2, knows, Iri("urn:c")),
                // A blank node that refers to itself.
                RdfTriple(Iri("urn:self"), q, BlankNode("loop")),
                RdfTriple(BlankNode("loop"), q, BlankNode("loop")),
                RdfTriple(BlankNode("loop"), p, string("loop")),
                // An IRI object is not followed.
                RdfTriple(Iri("urn:y"), knows, Iri("urn:x")),
                RdfTriple(Iri("urn:y"), p, string("why")),
            ),
        )
        repo.editGraph(Iri("urn:g1")).addTriples(
            listOf(
                RdfTriple(s, p, string("named1")),
                RdfTriple(Iri("urn:only-g1"), p, string("only1")),
                RdfTriple(Iri("urn:only-g1"), knows, s),
                RdfTriple(s, q, BlankNode("g1b")),
                RdfTriple(BlankNode("g1b"), p, string("named1-nested")),
            ),
        )
        repo.editGraph(Iri("urn:g2")).addTriple(RdfTriple(s, p, string("named2")))
    }

    /** Lexical forms of the literal objects described (an inferencing store also returns entailed triples). */
    private fun RdfRepository.described(query: String): List<String> =
        describe(SparqlDescribeQuery(query)).mapNotNull { (it.obj as? Literal)?.lexical }.distinct().sorted().toList()

    private val ofS = listOf("deep", "default", "nested")
    private val ofX = listOf("incoming")
    private val ofC = listOf("cycle1", "cycle2")

    private val cases: List<Pair<String, List<String>>> = listOf(
        "DESCRIBE <urn:s>" to ofS,
        "DESCRIBE ?s WHERE { ?s <urn:p> \"default\" }" to ofS,
        "DESCRIBE * WHERE { ?s <urn:p> \"default\" }" to ofS,
        // Incoming statements are not part of a description; the resources they come from are described when selected.
        "DESCRIBE <urn:x>" to ofX,
        "DESCRIBE ?x WHERE { ?x <urn:knows> <urn:s> }" to (ofX + "incoming-blank").sorted(),
        "DESCRIBE * WHERE { ?x <urn:knows> ?o FILTER(isIRI(?x)) }" to (ofS + ofX + "why").sorted(),
        "DESCRIBE <urn:y>" to listOf("why"),
        // Several resources.
        "DESCRIBE <urn:s> <urn:x>" to (ofS + ofX).sorted(),
        "DESCRIBE ?s <urn:x> WHERE { ?s <urn:p> \"why\" }" to (ofX + "why").sorted(),
        "DESCRIBE ?a ?b WHERE { ?a <urn:knows> ?b FILTER(?a = <urn:y>) }" to (ofX + "why").sorted(),
        // An IRI of the DESCRIBE clause is described whatever the WHERE clause matches.
        "DESCRIBE <urn:s> WHERE { ?a <urn:none> ?b }" to ofS,
        "DESCRIBE ?a <urn:s> WHERE { ?a <urn:none> ?b }" to ofS,
        "DESCRIBE <urn:absent>" to emptyList(),
        // Blank nodes: selected ones are described, chains are followed, cycles end.
        "DESCRIBE ?o WHERE { <urn:s> <urn:q> ?o }" to listOf("deep", "nested"),
        "DESCRIBE <urn:c>" to ofC,
        "DESCRIBE ?o WHERE { <urn:c> <urn:q> ?o }" to ofC,
        "DESCRIBE <urn:self>" to listOf("loop"),
        // Literals are not resources.
        "DESCRIBE ?o WHERE { <urn:s> <urn:p> ?o }" to emptyList(),
        // Solution modifiers select the resources.
        "DESCRIBE ?r WHERE { ?r <urn:p> ?o FILTER(isIRI(?r)) } ORDER BY ?o LIMIT 1" to ofS,
        "DESCRIBE ?r WHERE { ?r <urn:p> ?o FILTER(isIRI(?r)) } ORDER BY DESC(?o) LIMIT 1" to listOf("why"),
        // A resource found in a named graph is described from the default graph.
        "DESCRIBE ?s WHERE { GRAPH <urn:g2> { ?s ?p ?o } }" to ofS,
        "DESCRIBE ?s WHERE { GRAPH ?g { ?s ?p \"only1\" } }" to emptyList(),
        "DESCRIBE <urn:only-g1>" to emptyList(),
        "DESCRIBE ?s WHERE { ?s <urn:p> \"named1\" }" to emptyList(),
        "DESCRIBE ?s WHERE { ?s <urn:p> \"default\" GRAPH ?g { ?s <urn:p> \"named2\" } }" to ofS,
        // The query's own dataset: the description comes from its default graph, the merge of the FROM graphs.
        "DESCRIBE <urn:s> FROM <urn:g1>" to listOf("named1", "named1-nested"),
        "DESCRIBE <urn:s> FROM <urn:g1> FROM <urn:g2>" to listOf("named1", "named1-nested", "named2"),
        "DESCRIBE ?s FROM <urn:g1> WHERE { ?s <urn:p> \"only1\" }" to listOf("only1"),
        "DESCRIBE ?s FROM <urn:g1> WHERE { ?s <urn:knows> ?o }" to listOf("only1"),
        "DESCRIBE <urn:s> FROM NAMED <urn:g1>" to emptyList(),
        "DESCRIBE ?s FROM NAMED <urn:g1> WHERE { GRAPH ?g { ?s ?p ?o } }" to emptyList(),
        "DESCRIBE ?s FROM <urn:g2> FROM NAMED <urn:g1> WHERE { GRAPH ?g { ?s ?p \"named1\" } }" to listOf("named2"),
        "DESCRIBE ?s FROM <urn:g1> FROM NAMED <urn:g2> WHERE { GRAPH <urn:g2> { ?s ?p ?o } }" to listOf("named1", "named1-nested"),
    )

    @TestFactory
    fun `describe reads the default graph of the query dataset`(): List<DynamicTest> = cases.flatMap { (query, expected) ->
        providers.map { (provider, factory) ->
            DynamicTest.dynamicTest("$provider: $query") {
                populated(factory).use { repo -> assertEquals(expected, repo.described(query), query) }
            }
        }
    }

    /** The triples of a description with blank node labels (which are provider specific) made canonical. */
    private fun RdfRepository.canonicalDescription(query: String): Set<RdfTriple> {
        val triples = describe(SparqlDescribeQuery(query)).toList()
        // A blank node of this data is identified by the literals attached to it.
        val names = triples.filter { it.subject is BlankNode && it.obj is Literal }
            .groupBy({ it.subject }, { (it.obj as Literal).lexical }).mapValues { (_, lexicals) -> BlankNode(lexicals.sorted().joinToString("-")) }
        fun canonical(term: RdfTerm): RdfTerm = if (term is BlankNode) names[term] ?: BlankNode("unnamed") else term
        return triples.map { RdfTriple(canonical(it.subject) as com.geoknoesis.kastor.rdf.RdfResource, it.predicate, canonical(it.obj)) }.toSet()
    }

    @TestFactory
    fun `describe returns the same triples on both providers`(): List<DynamicTest> = cases.map { (query, _) ->
        DynamicTest.dynamicTest(query) {
            val jena = populated(providers.getValue("jena")).use { it.canonicalDescription(query) }
            val rdf4j = populated(providers.getValue("rdf4j")).use { it.canonicalDescription(query) }
            assertEquals(jena, rdf4j, query)
        }
    }

    @Test
    fun `a description holds the outgoing statements and the closure over blank nodes only`() {
        populated(providers.getValue("rdf4j")).use { repo ->
            val nested = BlankNode("nested")
            val deep = BlankNode("deep")
            assertEquals(
                setOf(
                    RdfTriple(s, p, string("default")),
                    RdfTriple(s, q, nested),
                    RdfTriple(nested, p, string("nested")),
                    RdfTriple(nested, q, deep),
                    RdfTriple(deep, p, string("deep")),
                ),
                repo.canonicalDescription("DESCRIBE <urn:s>"),
            )
        }
    }

    @Test
    fun `a description is the same inside a transaction and sees its uncommitted changes`() {
        populated(providers.getValue("rdf4j")).use { repo ->
            repo.transaction {
                editDefaultGraph().addTriple(RdfTriple(s, p, string("uncommitted")))
                assertEquals((ofS + "uncommitted").sorted(), described("DESCRIBE <urn:s>"))
            }
        }
    }

    private val a = Iri("urn:a")
    private val quoted = TripleTerm(RdfTriple(a, Iri("urn:b"), Iri("urn:c")))

    @Test
    fun `triple terms are described as objects and not followed on both providers`() {
        fun describe(factory: () -> RdfRepository): Set<RdfTriple> = factory().use { repo ->
            repo.editDefaultGraph().addTriples(
                listOf(RdfTriple(s, Iri("urn:r"), quoted), RdfTriple(a, p, string("inside")), RdfTriple(s, p, string("default"))),
            )
            repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toSet()
        }
        val expected = setOf(RdfTriple(s, Iri("urn:r"), quoted), RdfTriple(s, p, string("default")))
        assertEquals(expected, describe(providers.getValue("jena")))
        assertEquals(expected, describe(providers.getValue("rdf4j")))
    }

    @Test
    fun `reifiers are described as the graph API returns them`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            // An annotated triple is stored with an RDF-star subject; the graph API reads it as a reifier blank node.
            Rdf4jProvider().parseDataset(repo, "<< <urn:a> <urn:b> <urn:c> >> <urn:q> \"z\" .".byteInputStream(), "TURTLE")
            val graph = repo.editDefaultGraph()
            val annotation = graph.find(null, q, null).single()
            val reifier = annotation.subject as BlankNode
            val reifies = RdfTriple(reifier, RDF.reifies, quoted)
            // The explicit rdf:reifies triple is stored too: it must be described once.
            graph.addTriple(reifies)
            graph.addTriple(RdfTriple(s, Iri("urn:about"), reifier))
            graph.addTriple(RdfTriple(a, p, string("inside")))

            val description = repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList()
            assertEquals(setOf(RdfTriple(s, Iri("urn:about"), reifier), annotation, reifies), description.toSet())
            assertEquals(3, description.size, "no duplicate rdf:reifies triple: $description")
            // Every described triple is a triple of the graph.
            assertTrue(description.all(graph::hasTriple))
            // A reifier selected by the query is described like any blank node.
            assertEquals(
                setOf(annotation, reifies),
                repo.describe(SparqlDescribeQuery("DESCRIBE ?r WHERE { <urn:s> <urn:about> ?r }")).toSet(),
            )
        }
    }
}
