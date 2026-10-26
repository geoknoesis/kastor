package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Jena computes the value of every `xsd:integer` / `xsd:decimal` literal it parses, in time quadratic in the number of
 * digits (`BigInteger(String)`: about 12 s for 800,000 digits). Kastor refuses a numeric lexical form longer than
 * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] before Jena builds the node, on every parse path.
 */
class JenaNumericLiteralBoundTest {

    private val limit = LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH
    private val xsd = "http://www.w3.org/2001/XMLSchema#"
    private val tooLong = "7".repeat(limit + 1)
    private val longest = "7".repeat(limit)

    private fun turtle(obj: String) = "<http://example.org/s> <http://example.org/p> $obj ."

    private fun assertRefused(parse: () -> Any?) {
        val error = assertThrows(RdfFormatException::class.java) { parse() }
        assertTrue(error.message!!.contains("$limit"), error.message)
    }

    @Test
    fun `numeric literals longer than the limit are refused on every parse path`() {
        val documents = listOf(
            turtle(tooLong),
            turtle("$tooLong.5"),
            turtle("\"$tooLong\"^^<${xsd}integer>"),
            turtle("\"$tooLong\"^^<${xsd}long>"),
            turtle("\"$tooLong\"^^<${xsd}nonNegativeInteger>"),
            turtle("\"$tooLong\"^^<${xsd}decimal>"),
        )
        for (doc in documents) {
            assertRefused { JenaProvider().parseGraph(doc.byteInputStream(), "TURTLE") }
            assertRefused { JenaProvider().openTripleStream(doc.byteInputStream(), "TURTLE").use { it.toList() } }
            JenaRepository.MemoryRepository().use { repo -> assertRefused { JenaProvider().parseDataset(repo, doc.byteInputStream(), "TURTLE") } }
        }
        assertRefused { JenaProvider().parseGraph("<http://e/s> <http://e/p> \"$tooLong\"^^<${xsd}integer> .".byteInputStream(), "N-TRIPLES") }
        // Escaped digits count once decoded, and every syntax creates its literals through the bounded factory.
        assertRefused { JenaProvider().parseGraph(turtle("\"${"\\u0037".repeat(limit + 1)}\"^^<${xsd}integer>").byteInputStream(), "TURTLE") }
        val xml = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"http://e/s\">" +
            "<p xmlns=\"http://e/\" rdf:datatype=\"${xsd}decimal\">$tooLong</p></rdf:Description></rdf:RDF>"
        assertRefused { JenaProvider().parseGraph(xml.byteInputStream(), "RDF/XML") }
        val jsonld = "{\"@id\": \"http://e/s\", \"http://e/p\": {\"@value\": \"$tooLong\", \"@type\": \"${xsd}integer\"}}"
        assertRefused { JenaProvider().parseGraph(jsonld.byteInputStream(), "JSON-LD") }
    }

    @Test
    fun `numeric literals up to the limit, and long literals of other datatypes, parse`() {
        val documents = mapOf(
            turtle(longest) to TypedLiteral(longest, Iri("${xsd}integer")),
            turtle("\"$tooLong\"") to Literal(tooLong),
            turtle("\"$tooLong\"^^<${xsd}double>") to TypedLiteral(tooLong, Iri("${xsd}double")),
        )
        for ((doc, expected) in documents) {
            assertEquals(expected, JenaProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().single().obj)
        }
    }

    @Test
    fun `the graph API and SPARQL text refuse numbers longer than the limit`() {
        JenaRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            val error = assertThrows(IllegalArgumentException::class.java) {
                graph.addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), TypedLiteral(tooLong, Iri("${xsd}integer"))))
            }
            assertTrue(error.message!!.contains("$limit"), error.message)
            graph.addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), TypedLiteral(longest, Iri("${xsd}integer"))))
            assertEquals(1, graph.size())
            for (run in listOf(
                { repo.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p $tooLong }")) },
                { repo.ask(SparqlAskQuery("ASK { ?s ?p \"$tooLong\"^^<${xsd}decimal> }")) },
                { repo.update(UpdateQuery("INSERT DATA { <http://example.org/s> <http://example.org/p> $tooLong }")) },
            )) {
                val refused = assertThrows(RdfQueryException::class.java) { run() }
                assertTrue(refused.message!!.contains("$limit"), refused.message)
            }
            assertEquals(1, repo.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p $longest }")).toList().size)
            // Digits written as escapes, which Jena's parser decodes inside a string before it builds (and values) the
            // literal. (A bare escape is a lexical error to Jena; it is refused before Jena sees it all the same.)
            val escaped = "\\u0037".repeat(limit + 1)
            for (query in listOf(
                "SELECT * WHERE { ?s ?p $escaped }",
                "SELECT * WHERE { ?s ?p \"$escaped\"^^<${xsd}integer> }",
                "SELECT * WHERE { ?s ?p \"${"\\U00000037".repeat(limit + 1)}\"^^<${xsd}decimal> }",
                "SELECT * WHERE { ?s ?p \"${"7".repeat(limit / 2)}${"\\u0037".repeat(limit / 2 + 1)}\"^^<${xsd}long> }",
            )) {
                val refused = assertThrows(RdfQueryException::class.java, { repo.select(SparqlSelectQuery(query)) }, query.take(60))
                assertTrue(refused.message!!.contains("$limit"), refused.message)
            }
            // An escape that is not a digit ends a run.
            val split = "${"7".repeat(limit)}\\u0020${"7".repeat(limit)}"
            assertEquals(0, repo.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p \"$split\" }")).toList().size)
        }
    }

    @Test
    fun `temporal literals longer than the limit are refused, as Jena compares their seconds in quadratic time`() {
        // Measured: a SPARQL comparison of a dateTime with 400,000 fraction digits took 2.5 s, with 200,000 0.6 s.
        val fraction = "1".repeat(limit)
        for ((lexical, type) in listOf(
            "2020-01-01T00:00:00.$fraction" to "dateTime", "00:00:00.$fraction" to "time",
            "PT1.${fraction}S" to "duration", "1$fraction" to "gYear", "1$fraction-01-01" to "date",
        )) {
            // A parse error: the limit, or (for fractional seconds) Jena's own checker, which cannot read them.
            assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(turtle("\"$lexical\"^^<$xsd$type>").byteInputStream(), "TURTLE") }
            JenaRepository.MemoryRepository().use { repo ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    repo.editDefaultGraph().addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), TypedLiteral(lexical, Iri(xsd + type))))
                }
                assertTrue(error.message!!.contains("$limit"), error.message)
            }
        }
    }

    @Test
    fun `a dateTime whose fraction Jena cannot read fails as a parse error, not a NumberFormatException`() {
        // Jena's literal checker reads the fractional seconds as an int: 18 digits overflow it.
        val doc = turtle("\"2020-01-01T00:00:00.${"1".repeat(18)}\"^^<${xsd}dateTime>")
        val error = assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(doc.byteInputStream(), "TURTLE") }
        assertTrue(error.message!!.contains("dateTime") || error.message!!.contains("format"), error.message)
        assertThrows(RdfFormatException::class.java) { JenaProvider().openTripleStream(doc.byteInputStream(), "TURTLE").use { it.toList() } }
    }

    @Test
    fun `a decimal's digits count across its point and exponent in SPARQL text`() {
        val half = "7".repeat(limit / 2 + 1)
        JenaRepository.MemoryRepository().use { repo ->
            for (number in listOf("$half.$half", "\"$half.$half\"^^<${xsd}decimal>", "${half}e$half", "$half.${half}E+1")) {
                val refused = assertThrows(RdfQueryException::class.java, { repo.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p $number }")) }, number.take(40))
                assertTrue(refused.message!!.contains("$limit"), refused.message)
            }
            val short = "7".repeat(limit / 2 - 1)
            assertEquals(0, repo.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p $short.$short }")).toList().size)
        }
    }
}
