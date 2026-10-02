package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * Differential test of the keyword scanner ([SparqlDatasetClauses]) against Jena's SPARQL parser, on generated
 * corpora of several seeds: queries of every form, with and without dataset clauses and `GRAPH` patterns, minified or not, and full of
 * look-alikes - `GRAPH` and `FROM` in strings, long strings, comments, IRIs, prefixed names, variables and language
 * tags, numbers and booleans glued to a keyword, and codepoint escapes inside strings and IRIs.
 *
 * Jena is the oracle for what a query contains (dataset clauses: the parsed query; `GRAPH`: its algebra) and for
 * whether a rewritten query is still SPARQL. Jena does not read a codepoint escape outside strings and IRIs, so a
 * query with an escaped keyword is judged by the same query with the keyword written out.
 *
 * Jena is on the test runtime class path only, hence the reflection. `SparqlScannerRdf4jOracleTest` in `rdf:testkit`
 * runs the scanner against a second, independent oracle: RDF4J's parser, which reads codepoint escapes the other way.
 */
class SparqlScannerDifferentialTest {
    /** A backslash followed by `u`, put together so that no codepoint escape is written in this file. */
    private val U = "\\" + "u"
    private val BS = "\\"

    private val queryClass = Class.forName("org.apache.jena.query.Query")
    private val create = Class.forName("org.apache.jena.query.QueryFactory").getMethod("create", String::class.java)
    private val compile = Class.forName("org.apache.jena.sparql.algebra.Algebra").getMethod("compile", queryClass)
    private val hasDataset = queryClass.getMethod("hasDatasetDescription")
    private val graphUris = queryClass.getMethod("getGraphURIs")

    private fun parse(query: String): Any? = try {
        create.invoke(null, query)
    } catch (_: InvocationTargetException) {
        null
    }

    private fun parseError(query: String): String? = try {
        create.invoke(null, query)
        null
    } catch (e: InvocationTargetException) {
        e.targetException.message
    }

    /** True if the algebra of the parsed query has a graph operator (the SSE form writes it as `(graph `). */
    private fun hasGraphOperator(parsed: Any): Boolean = compile.invoke(null, parsed).toString().contains("(graph ")

    /** One generated query: [text] as given to the scanner, [plain] with its escaped keywords written out. */
    private class Generated(val text: String, val plain: String, val hasService: Boolean)

    private inner class Generator(seed: Long) {
        private val random = java.util.Random(seed)
        private var oddQuote = false
        private var service = false

        private fun <T> pick(vararg options: T): T = options[random.nextInt(options.size)]
        private fun chance(percent: Int) = random.nextInt(100) < percent

        /** `%G%` and `%F%` stand for the GRAPH and FROM keywords, sometimes spelled with an escape. */
        private fun graphKeyword() = pick("GRAPH", "GRAPH", "GRAPH", "graph", "GrApH", "%G%")
        private fun fromKeyword() = pick("FROM", "FROM", "from", "%F%")

        /** Set when the term just generated must be the last string of its line. */
        private var endLine = false

        private fun stringTerm(): String {
            if (!oddQuote && chance(8)) {
                // Decoded first, the escaped quote ends the string and the real closing quote opens one that the
                // end of the line leaves unterminated: no engine accepts that reading, so only one reading is left.
                // (With another string on the same line both readings would be token sequences, and differ.)
                oddQuote = true
                endLine = true
                return pick("\"a${U}0022b\"", "'a${U}0027b'")
            }
            return pick(
                "\"GRAPH ?g { ?s ?p ?o }\"", "'FROM <urn:x>'", "\"\"\"a \"GRAPH\" b\nFROM <urn:x>\"\"\"", "'''it's a GRAPH'''",
                "\"x\"@from", "\"x\"@en-GRAPH", "\"q$BS\"GRAPH$BS\" FROM\"", "\"tab${BS}t # GRAPH\"", "'# FROM'",
                "\"caf${U}00E9 GRAPH\"", "\"\"\"a${U}000Ab GRAPH\"\"\"", "\"$BS${U}0041 FROM\"", "\"l1${U}000Al2\"",
                "\"a${U}005C${U}005Cb\"", "\"${BS}U0001F600\"", "\"x\"^^<urn:GRAPH>",
            )
        }

        /** An object term, and whether a dot may follow it without a space (not after a prefixed name). */
        private fun objectTerm(): Pair<String, Boolean> = when (random.nextInt(12)) {
            0, 1 -> pick("?o", "?graph", "?from", "\$GRAPH") to true
            2 -> pick("ex:o", "ex:GRAPH", "ex:from", "\"x\"^^ex:from", ":GRAPH") to false
            3 -> pick("<urn:FROM>", "<urn:GRAPH#x>", "<urn:${U}0070>") to true
            4 -> pick("1", "1.5", "1e3", ".5", "1.0e-2", "true", "false") to true
            else -> stringTerm() to true
        }

        private fun triple(): String {
            val predicate = pick("ex:p", "ex:GRAPH", "ex:from", "<urn:GRAPH>", "a", "ex:a$BS#GRAPH", ":from")
            endLine = false
            val (obj, tight) = objectTerm()
            val dot = if (endLine) " .\n" else if (tight) pick(" . ", ".", " .\n", ". ") else pick(" . ", " .\n")
            return "?s $predicate $obj$dot"
        }

        private fun elements(depth: Int): String = (1..1 + random.nextInt(3)).joinToString("") { element(depth) }

        private fun element(depth: Int): String = when (random.nextInt(if (depth >= 3) 5 else 14)) {
            0, 1, 2 -> triple()
            3 -> "FILTER(?o != ${stringTerm()})\n"
            4 -> "# ${pick("GRAPH ?g { ?s ?p ?o }", "FROM <urn:x>", "\" GRAPH", "' FROM", "caf${U}00E9")}\n"
            5, 6, 7 -> {
                val name = pick("?g", "<urn:g1>", "ex:g", "\$g")
                val open = pick(" { ", "{", "\n{ ")
                "${graphKeyword()} $name$open${elements(depth + 1)}}${pick(" ", "", "\n")}"
            }
            8 -> "OPTIONAL { ${elements(depth + 1)}} "
            9 -> "{ ${elements(depth + 1)}} UNION { ${elements(depth + 1)}} "
            10 -> "FILTER NOT EXISTS { ${elements(depth + 1)}} "
            11 -> "MINUS { ${elements(depth + 1)}} "
            12 -> "{ SELECT ?s WHERE { ${elements(depth + 1)}} } "
            else -> {
                service = true
                "SERVICE ${pick("", "SILENT ")}<urn:ep> { ${elements(depth + 1)}} "
            }
        }

        fun query(): Generated {
            oddQuote = false
            service = false
            val prologue = (if (chance(30)) "# FROM <urn:c> GRAPH ?c { }\n" else "") +
                "PREFIX ex: <urn:ex:>\nPREFIX : <urn:d:>\n" + (if (chance(20)) "BASE <urn:base:>\n" else "")
            val head = pick(
                "SELECT ?s", "SELECT *", "SELECT DISTINCT ?s ?o", "SELECT (COUNT(*) AS ?n)", "SELECT (\"FROM\" AS ?x) ?s",
                "SELECT ?s ?from", "ASK", "CONSTRUCT { ?s ex:from ?o }", "DESCRIBE ?s", "DESCRIBE <urn:GRAPH> ?s",
            )
            val dataset = if (!chance(30)) "" else pick(
                " ${fromKeyword()} <urn:g1>", " ${fromKeyword()} NAMED <urn:g2>", "\n${fromKeyword()}<urn:g1>",
                " from <urn:g1> ${fromKeyword()} named <urn:g2>",
            )
            val where = pick(" WHERE ", " ", "\nwhere", "", " WHERE")
            val template = "$prologue$head$dataset$where{ ${elements(0)}}${pick("", " LIMIT 5", "\nLIMIT 5")}"
            val plain = template.replace("%G%", "GRAPH").replace("%F%", "FROM")
            val text = template
                .replace("%G%", pick("${U}0047RAPH", "GR${U}0041PH", "GRAP${BS}U00000048"))
                .replace("%F%", pick("${U}0046ROM", "FRO${U}004D"))
            return Generated(text, plain, service)
        }
    }

    /** One corpus per seed: a finding of one seed must not hide behind the luck of another. */
    private val seeds = listOf(20261001L, 1L, 42L, 8675309L, 314159265L, 2718281828L)

    @Test
    fun `the scanner agrees with Jena's parser on generated corpora of several seeds`() {
        for (seed in seeds) checkCorpus(seed)
    }

    private fun checkCorpus(seed: Long) {
        val generator = Generator(seed)
        val corpus = List(260) { generator.query() }
        var withGraph = 0
        var withDataset = 0
        var withEscapes = 0
        var escapedKeywords = 0
        for (generated in corpus) {
            val text = generated.text
            val parsed = parse(generated.plain)
            assertNotNull(parsed, "the generator produced a query Jena rejects (${parseError(generated.plain)}):\n${generated.plain}")
            val jenaDataset = hasDataset.invoke(parsed) as Boolean
            val jenaGraph = hasGraphOperator(parsed!!)
            if (jenaGraph) withGraph++
            if (jenaDataset) withDataset++
            if (text.contains(U) || text.contains("${BS}U")) withEscapes++
            if (text != generated.plain) escapedKeywords++

            assertEquals(jenaDataset, SparqlDatasetClauses.declaresDataset(text), "FROM in:\n$text")
            assertEquals(jenaGraph, SparqlDatasetClauses.usesGraphPattern(text), "GRAPH in:\n$text")
            // One text for both readings of the escapes: the query as written, or with its escaped keywords decoded.
            assertEquals(generated.plain, SparqlDatasetClauses.canonical(text), "canonical form of:\n$text")

            // The GRAPH rewrite: still SPARQL, and no graph operator left outside SERVICE.
            val withoutGraphs = SparqlDatasetClauses.withoutNamedGraphs(text)
            assertNotNull(withoutGraphs, "GRAPH rewrite of:\n$text")
            val reparsed = parse(withoutGraphs!!)
            assertNotNull(reparsed, "the rewritten query is not SPARQL (${parseError(withoutGraphs)}):\n$withoutGraphs\nfrom:\n$text")
            if (!jenaGraph) assertEquals(generated.plain, withoutGraphs, "a query without GRAPH is not changed")
            if (!generated.hasService) assertFalse(hasGraphOperator(reparsed!!), "GRAPH left in:\n$withoutGraphs")
            assertEquals(jenaDataset, hasDataset.invoke(reparsed) as Boolean)

            // The dataset clauses: inserted where Jena reads them as the query's dataset.
            if (!jenaDataset) {
                val inserted = SparqlDatasetClauses.insert(text, "FROM <urn:inserted>")
                assertNotNull(inserted, "dataset clause insertion into:\n$text")
                val withClauses = parse(inserted!!)
                assertNotNull(withClauses, "the query with dataset clauses is not SPARQL (${parseError(inserted)}):\n$inserted")
                assertEquals(listOf("urn:inserted"), graphUris.invoke(withClauses), inserted)
                assertEquals(jenaGraph, hasGraphOperator(withClauses!!), inserted)
            }
        }
        // The corpus exercises what it is meant to.
        assertTrue(withGraph in 50..235, "seed $seed: queries with GRAPH: $withGraph")
        assertTrue(withDataset in 35..125, "seed $seed: queries with dataset clauses: $withDataset")
        assertTrue(withEscapes >= 50, "seed $seed: queries with codepoint escapes: $withEscapes")
        assertTrue(escapedKeywords >= 10, "seed $seed: queries with an escaped keyword: $escapedKeywords")
    }
}
