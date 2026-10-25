package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QuerySolutionMap
import org.apache.jena.query.ResultSetFormatter
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.update.UpdateAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Initial bindings are substituted into the query text with the same results as Jena substitution. */
class SparqlEndpointInitialBindingsTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    private fun key(term: RdfTerm?): String = when (term) {
        null -> "UNDEF"
        is Iri -> term.value
        is LangString -> "${term.lexical}@${term.lang.lowercase()}"
        is Literal -> if (term.datatype == XSD.string) term.lexical else "${term.lexical}^^${term.datatype.value}"
        else -> term.toString()
    }

    private fun key(node: RDFNode): String = when {
        node.isURIResource -> node.asResource().uri
        node.isLiteral -> node.asLiteral().let { l ->
            when {
                l.language.isNotEmpty() -> "${l.lexicalForm}@${l.language.lowercase()}"
                l.datatypeURI == XSD.string.value -> l.lexicalForm
                else -> "${l.lexicalForm}^^${l.datatypeURI}"
            }
        }
        else -> node.toString()
    }

    private fun jenaSubstitution(dataset: Dataset, query: String, bindings: Map<String, RDFNode>): List<Map<String, String>> {
        val initial = QuerySolutionMap()
        bindings.forEach { (name, node) -> initial.add(name, node) }
        return QueryExecution.dataset(dataset).query(query).substitution(initial).build().use { exec ->
            exec.execSelect().asSequence().map { row -> row.varNames().asSequence().associateWith { key(row.get(it)) } }.toList()
        }.sortedBy { it.toString() }
    }

    @Test
    fun `initial bindings restrict the query exactly like Jena substitution`() {
        val dataset = DatasetFactory.create()
        RDFParser.fromString(
            "<urn:a> <urn:p> 1 . <urn:a> <urn:p> 2 . <urn:b> <urn:p> 3 . <urn:a> <urn:l> \"x\"@en . <urn:a> <urn:l> \"y\"@fr . " +
                "<urn:b> <urn:l> \"z\"@en . <urn:b> <urn:q> 9 .",
            Lang.TURTLE,
        ).parse(dataset.asDatasetGraph())
        val a = Iri("urn:a") to ResourceFactory.createResource("urn:a")
        val b = Iri("urn:b") to ResourceFactory.createResource("urn:b")
        val en = Literal("en") to ResourceFactory.createPlainLiteral("en")
        val cases = listOf(
            "aggregate COUNT" to ("SELECT (COUNT(*) AS ?c) WHERE { ?s <urn:p> ?o }" to mapOf("s" to a)),
            "GROUP BY bound variable" to ("SELECT ?s (COUNT(*) AS ?c) WHERE { ?s <urn:p> ?o } GROUP BY ?s" to mapOf("s" to a)),
            "FILTER with bound variable" to ("SELECT ?s ?l WHERE { ?s <urn:l> ?l FILTER(lang(?l) = ?lang) }" to mapOf("lang" to en)),
            "sub-select with LIMIT" to ("SELECT ?s ?o WHERE { { SELECT ?s WHERE { ?s <urn:p> ?x } LIMIT 1 } ?s <urn:p> ?o }" to mapOf("s" to b)),
            "OPTIONAL" to ("SELECT ?s ?z WHERE { ?s <urn:p> ?o OPTIONAL { ?s <urn:q> ?z } }" to mapOf("s" to b)),
            "MINUS" to ("SELECT ?s ?o WHERE { ?s <urn:p> ?o MINUS { ?s <urn:q> ?z } }" to mapOf("s" to b)),
            "NOT EXISTS" to ("SELECT ?s ?o WHERE { ?s <urn:p> ?o FILTER NOT EXISTS { ?s <urn:q> ?z } }" to mapOf("s" to b)),
            "projection with prefixes comments and strings" to (
                "PREFIX ex: <urn:>\n# ?s in a comment\nSELECT DISTINCT ?s ?o (\"?s\" AS ?txt) WHERE { ?s ex:p ?o . FILTER(?o != \"?s\") } ORDER BY ?s ?o" to
                    mapOf("s" to a)),
            "SELECT star" to ("SELECT * WHERE { ?s <urn:p> ?o }" to mapOf("s" to a)),
            "dollar variables" to ("SELECT \$s ?o WHERE { \$s <urn:p> ?o }" to mapOf("s" to b)),
            "unused binding" to ("SELECT ?o WHERE { ?x <urn:p> ?o }" to mapOf("s" to b)),
        )
        LocalEndpoint(jenaProtocolHandler(dataset)).use { endpoint ->
            SparqlRepository(endpoint.url).use { repo ->
                for ((name, case) in cases) {
                    val (query, bindings) = case
                    val expected = jenaSubstitution(dataset, query, bindings.mapValues { it.value.second })
                    val actual = repo.withSelectRows(SparqlSelectQuery(query), bindings.mapValues { it.value.first }, Duration.ofSeconds(10)) { rows ->
                        rows.map { row -> row.getVariableNames().associateWith { key(row.get(it)) } }.toList()
                    }.sortedBy { it.toString() }
                    assertEquals(expected, actual, "$name:\n${endpoint.requests.last().body}")
                }
            }
        }
        dataset.close()
    }

    @Test
    fun `initial bindings that cannot be substituted are rejected before sending`(): Unit = LocalEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(0))
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val bound = mapOf<String, RdfTerm>("s" to Iri("urn:a"))
            listOf(
                "SELECT ?s WHERE { BIND(<urn:a> AS ?s) }",
                "SELECT (?o AS ?s) WHERE { ?x <urn:p> ?o }",
                "SELECT ?s WHERE { VALUES ?s { <urn:a> } }",
                "SELECT ?o WHERE { ?x <urn:p> ?o } VALUES (?o ?s) { (1 <urn:a>) }",
                "SELECT ?o ?c WHERE { ?s <urn:p> ?o { SELECT (COUNT(?s) AS ?c) WHERE { ?s <urn:p> ?x } } }",
            ).forEach { query ->
                assertThrows(IllegalArgumentException::class.java, {
                    repo.withSelectRows(SparqlSelectQuery(query), bound, Duration.ofSeconds(1)) { it.count() }
                }, query)
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.withSelectRows(selectAll, mapOf("s" to BlankNode("b")), Duration.ofSeconds(1)) { it.count() }
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.withSelectRows(SparqlAskQuery("ASK { ?s ?p ?o }").let { SparqlSelectQuery(it.sparql) }, bound, Duration.ofSeconds(1)) { it.count() }
            }
        }
        assertTrue(endpoint.requests.isEmpty())
    }
}
