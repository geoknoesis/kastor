package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The RDF4J provider's parses apply the numeric literal limit the Jena provider needs, for parity between them. */
class Rdf4jNumericLiteralBoundTest {

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
        for (doc in listOf(turtle(tooLong), turtle("$tooLong.5"), turtle("\"$tooLong\"^^<${xsd}long>"), turtle("\"$tooLong\"^^<${xsd}decimal>"))) {
            assertRefused { Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE") }
            assertRefused { Rdf4jProvider().openTripleStream(doc.byteInputStream(), "TURTLE").use { it.toList() } }
            Rdf4jRepository.MemoryRepository().use { repo -> assertRefused { Rdf4jProvider().parseDataset(repo, doc.byteInputStream(), "TURTLE") } }
        }
        assertRefused { Rdf4jProvider().parseGraph(turtle("<<( <http://e/a> <http://e/b> $tooLong )>>").byteInputStream(), "TURTLE") }
        // The parsed lexical form is checked, so escapes and every syntax are covered.
        assertRefused { Rdf4jProvider().parseGraph(turtle("\"${"\\u0037".repeat(limit + 1)}\"^^<${xsd}integer>").byteInputStream(), "TURTLE") }
        val xml = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"http://e/s\">" +
            "<p xmlns=\"http://e/\" rdf:datatype=\"${xsd}decimal\">$tooLong</p></rdf:Description></rdf:RDF>"
        assertRefused { Rdf4jProvider().parseGraph(xml.byteInputStream(), "RDF/XML") }
        val jsonld = "{\"@id\": \"http://e/s\", \"http://e/p\": {\"@value\": \"$tooLong\", \"@type\": \"${xsd}integer\"}}"
        assertRefused { Rdf4jProvider().parseGraph(jsonld.byteInputStream(), "JSON-LD") }
    }

    @Test
    fun `numeric literals up to the limit, and long literals of other datatypes, parse`() {
        for ((doc, expected) in mapOf(
            turtle(longest) to TypedLiteral(longest, Iri("${xsd}integer")),
            turtle("\"$tooLong\"") to Literal(tooLong),
            turtle("\"$tooLong\"^^<${xsd}double>") to TypedLiteral(tooLong, Iri("${xsd}double")),
        )) {
            assertEquals(expected, Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().single().obj)
        }
    }

    @Test
    fun `temporal literals longer than the limit are refused, as on Jena`() {
        val fraction = "1".repeat(limit)
        for ((lexical, type) in listOf(
            "2020-01-01T00:00:00.$fraction" to "dateTime", "00:00:00.$fraction" to "time",
            "PT1.${fraction}S" to "duration", "1$fraction" to "gYear", "1$fraction-01-01" to "date",
        )) {
            assertRefused { Rdf4jProvider().parseGraph(turtle("\"$lexical\"^^<$xsd$type>").byteInputStream(), "TURTLE") }
        }
        val accepted = "2020-01-01T00:00:00.${"1".repeat(100)}"
        assertEquals(TypedLiteral(accepted, Iri("${xsd}dateTime")), Rdf4jProvider().parseGraph(turtle("\"$accepted\"^^<${xsd}dateTime>").byteInputStream(), "TURTLE").getTriples().single().obj)
    }

    /**
     * Round sixteen, providers P2-7: the graph API and SPARQL UPDATE skipped the limit, and RDF4J's MemoryStore turns
     * an integer or decimal into a `BigInteger` when it stores it, in quadratic time (two 1,000,000-digit integers
     * inserted by an UPDATE took 33 s). Both are refused now, with the parse path's message, as on Jena.
     */
    @Test
    fun `the graph API and SPARQL UPDATE refuse numeric and temporal literals longer than the limit`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            for ((lexical, type) in listOf(tooLong to "integer", "$tooLong.5" to "decimal", "2020-01-01T00:00:00.$tooLong" to "dateTime")) {
                val literal = TypedLiteral(lexical, Iri(xsd + type))
                val error = assertThrows(IllegalArgumentException::class.java) {
                    graph.addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), literal))
                }
                assertTrue(error.message!!.contains("$limit"), error.message)
                assertThrows(IllegalArgumentException::class.java) {
                    graph.addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), TripleTerm(RdfTriple(Iri("http://e/a"), Iri("http://e/b"), literal))))
                }
                for (update in listOf(
                    "INSERT DATA { <http://example.org/s> <http://example.org/p> \"$lexical\"^^<$xsd$type> }",
                    "INSERT { ?s <http://example.org/p> \"$lexical\"^^<$xsd$type> } WHERE { BIND(<http://example.org/s> AS ?s) }",
                    "INSERT DATA { <http://example.org/s> <http://example.org/p> \"${"\\u0037".repeat(limit + 1)}\"^^<${xsd}integer> }",
                )) {
                    val refused = assertThrows(RdfQueryException::class.java, { repo.update(UpdateQuery(update)) }, update.take(80))
                    assertTrue(refused.message!!.contains("$limit"), refused.message)
                }
            }
            assertEquals(0, graph.size())
            graph.addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), TypedLiteral(longest, Iri("${xsd}integer"))))
            repo.update(UpdateQuery("INSERT DATA { <http://example.org/s> <http://example.org/q> $longest }"))
            repo.update(UpdateQuery("INSERT DATA { <http://example.org/s> <http://example.org/r> \"$longest 7\" }"))
            assertEquals(3, graph.size())
        }
    }

    /**
     * Round sixteen, security review: the text of an update holds no long number when the update computes it, or
     * reads it with `LOAD`. Kastor's stores check what is written, so every one of these is refused too.
     */
    @Test
    fun `SPARQL UPDATE refuses long numeric and temporal literals it computes or loads`() {
        val half = "7".repeat(limit / 2 + 1)
        val fraction = "1".repeat(limit / 2 + 1)
        val doc = kotlin.io.path.createTempFile("kastor-long", ".ttl").toFile().apply {
            writeText(turtle(tooLong))
            deleteOnExit()
        }
        for (store in listOf("memory", "native")) {
            val dir = kotlin.io.path.createTempDirectory("kastor-native").toFile()
            val repo = if (store == "memory") Rdf4jRepository.MemoryRepository() else Rdf4jRepository.NativeRepository(dir.path)
            repo.use {
                for (update in listOf(
                    "INSERT { <http://e/s> <http://e/p> ?o } WHERE { BIND(STRDT(CONCAT(\"$half\", \"$half\"), <${xsd}integer>) AS ?o) }",
                    "INSERT { <http://e/s> <http://e/p> ?o } WHERE { BIND(STRDT(REPLACE(\"$half\", \"7\", \"77\"), <${xsd}long>) AS ?o) }",
                    "INSERT { <http://e/s> <http://e/p> ?o } WHERE { BIND(STRDT(CONCAT(\"$half\", \".\", \"$half\"), <${xsd}decimal>) AS ?o) }",
                    "INSERT { <http://e/s> <http://e/p> ?o } WHERE { BIND(STRDT(CONCAT(\"2020-01-01T00:00:00.\", \"$fraction\", \"$fraction\"), <${xsd}dateTime>) AS ?o) }",
                    "LOAD <${doc.toURI()}>",
                )) {
                    val refused = assertThrows(RdfQueryException::class.java, { repo.update(UpdateQuery(update)) }, "$store: ${update.take(80)}")
                    assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { it.message.orEmpty().contains("$limit") }, "$store: ${refused.message}")
                }
                // RDF4J's cast to xsd:integer of so long a string is an error (?o stays unbound): nothing is written.
                runCatching { repo.update(UpdateQuery("INSERT { <http://e/s> <http://e/p> ?o } WHERE { BIND(<${xsd}integer>(CONCAT(\"$half\", \"$half\")) AS ?o) }")) }
                assertEquals(0, repo.editDefaultGraph().size(), store)
                // What fits is written.
                repo.update(UpdateQuery("INSERT { <http://e/s> <http://e/p> ?o } WHERE { BIND(STRDT(CONCAT(\"7\", \"7\"), <${xsd}integer>) AS ?o) }"))
                assertEquals(1, repo.editDefaultGraph().size(), store)
            }
            dir.deleteRecursively()
        }
    }

    @Test
    fun `numbers of non-ASCII decimal digits count as numbers in SPARQL UPDATE text`() {
        // BigInteger reads every Unicode decimal digit; a 1,000,000-character lexical form of them is refused at once.
        Rdf4jRepository.MemoryRepository().use { repo ->
            for (digit in listOf("١", "１")) {
                val update = "INSERT DATA { <http://e/s> <http://e/p> \"${digit.repeat(1_000_000)}\"^^<${xsd}integer> }"
                val started = System.nanoTime()
                val refused = assertThrows(RdfQueryException::class.java) { repo.update(UpdateQuery(update)) }
                assertTrue(refused.message!!.contains("$limit"), refused.message)
                assertTrue(System.nanoTime() - started < 5_000_000_000L, "refused, but slowly")
            }
        }
    }
}
