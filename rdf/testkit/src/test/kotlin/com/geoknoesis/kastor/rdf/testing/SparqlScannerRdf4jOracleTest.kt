package com.geoknoesis.kastor.rdf.testing

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Differential test of core's SPARQL keyword scanner and GRAPH rewrite (`SparqlDatasetClauses`) with **RDF4J's**
 * SPARQL parser as the oracle, on generated corpora of several seeds. (`SparqlScannerDifferentialTest` in `rdf:core`
 * uses Jena's parser; two parsers written independently are less likely to share a misreading with the scanner.)
 *
 * RDF4J also reads codepoint escapes the way the SPARQL grammar says - decoded before the query is tokenized - which
 * Jena does not: a keyword spelled with an escape (GRAPH with its first letter as a codepoint escape) is a keyword
 * to RDF4J, so the scanner is checked against it on the text exactly as written.
 *
 * What is checked for every query:
 * - `declaresDataset` and `usesGraphPattern` agree with what RDF4J parsed;
 * - the GRAPH rewrite for a dataset without named graphs is still SPARQL, leaves no pattern that reads a named
 *   graph (outside `SERVICE`), and - for `SELECT *` - projects exactly the variables the original query projects:
 *   the variables in scope are unchanged, also for patterns with `FILTER`, `BIND`, `MINUS`, `VALUES` and sub-selects;
 * - dataset clauses are inserted where RDF4J reads them as the query's dataset.
 *
 * The scanner is internal to `rdf:core`; it is reached by reflection, like the parser, which is on the runtime
 * class path only.
 */
class SparqlScannerRdf4jOracleTest {
    /** A backslash followed by `u`, put together so that no codepoint escape is written in this file. */
    private val u = "\\" + "u"

    private val parser = Class.forName("org.eclipse.rdf4j.query.parser.sparql.SPARQLParser").getConstructor().newInstance()
    private val parseQuery = parser.javaClass.getMethod("parseQuery", String::class.java, String::class.java)

    private val scanner = Class.forName("com.geoknoesis.kastor.rdf.SparqlDatasetClauses").let { type ->
        type.getField("INSTANCE").get(null)
    }

    private fun scan(method: String, vararg arguments: String): Any? =
        scanner.javaClass.getMethod(method, *arguments.map { String::class.java }.toTypedArray()).invoke(scanner, *arguments)

    /** What RDF4J made of a query. */
    private class Parsed(val dataset: String?, val algebra: String) {
        val readsNamedGraphs: Boolean get() = algebra.contains("FROM NAMED CONTEXT")

        /**
         * The variables of the outermost projection, in order: for `SELECT *`, the variables RDF4J has in scope.
         *
         * RDF4J also projects the variables of a `MINUS` group, which SPARQL 1.1 (section 18.2.1) does not put in
         * scope (and Jena does not project). The generator gives `MINUS` groups variables of their own, `?m1`,
         * `?m2`, ..., and they are left out here; everything else is compared.
         */
        val projected: List<String>
            get() = algebra.lineSequence().dropWhile { !it.trim().startsWith("ProjectionElemList") }.drop(1)
                .takeWhile { it.trim().startsWith("ProjectionElem ") }
                .map { it.trim().removePrefix("ProjectionElem ").trim('"') }
                .filterNot { MINUS_VARIABLE.matches(it) }.toList()
    }

    private fun parse(query: String): Parsed? = try {
        val parsed = parseQuery.invoke(parser, query, null)
        val dataset: Any? = parsed.javaClass.getMethod("getDataset").invoke(parsed)
        Parsed(dataset?.toString(), parsed.javaClass.getMethod("getTupleExpr").invoke(parsed).toString())
    } catch (_: InvocationTargetException) {
        null
    }

    private fun parseError(query: String): String? = try {
        parseQuery.invoke(parser, query, null)
        null
    } catch (e: InvocationTargetException) {
        e.targetException.message
    }

    private companion object {
        val MINUS_VARIABLE = Regex("m[0-9]+")
    }

    private class Generated(val text: String, val selectsAll: Boolean, val hasService: Boolean)

    private inner class Generator(seed: Long) {
        private val random = java.util.Random(seed)
        private var service = false
        private var fresh = 0

        private fun <T> pick(vararg options: T): T = options[random.nextInt(options.size)]
        private fun chance(percent: Int) = random.nextInt(100) < percent

        /** The GRAPH and FROM keywords, in any case and sometimes spelled with a codepoint escape. */
        private fun graphKeyword() = pick("GRAPH", "GRAPH", "graph", "GrApH", "${u}0047RAPH", "GR${u}0041PH", "GRAP${u}0048")
        private fun fromKeyword() = pick("FROM", "FROM", "from", "${u}0046ROM", "FRO${u}004D")

        /** Object terms full of look-alikes; the escapes in strings do not change where a string ends. */
        private fun term(): String = pick(
            "?o", "?o", "?graph", "?from", "\"GRAPH ?g { ?s ?p ?o }\"", "'FROM <urn:x>'", "\"x\"@from", "\"x\"@en-GRAPH",
            "\"\"\"a \"GRAPH\" b\nFROM <urn:x>\"\"\"", "'''a 'GRAPH' b'''", "\"caf${u}00E9 GRAPH\"", "<urn:GRAPH#x>", "<urn:FROM>",
            "ex:o", "ex:GRAPH", "ex:from", "1", "1.5", "true", "\"x\"^^<urn:GRAPH>",
        )

        private fun triple(): String =
            "${pick("?s", "?s", "?a")} ${pick("ex:p", "ex:GRAPH", "ex:from", "<urn:GRAPH>", "a", "?p")} ${term()} ${pick(". ", ".\n", " . ")}"

        private fun elements(depth: Int, inGraph: Boolean): String =
            // A GRAPH group starts with a triple: the oracle sees a named graph being read.
            (if (inGraph) triple() else "") + (1..random.nextInt(4)).joinToString("") { element(depth) }

        private fun element(depth: Int): String = when (random.nextInt(if (depth >= 3) 9 else 14)) {
            0, 1 -> triple()
            2 -> "FILTER(?o != ${term()}) "
            3 -> "FILTER NOT EXISTS { ${triple()}} "
            4 -> "FILTER regex(str(?o), \"GRAPH\") "
            5 -> "BIND(concat(str(?o), \"FROM\") AS ?b${fresh++}) "
            // Variables of its own (see [Parsed.projected] for why).
            6 -> "MINUS { ?m${fresh++} ${pick("ex:p", "ex:GRAPH", "a")} ?m${fresh} FILTER(?m${fresh++} > 1) } "
            7 -> "VALUES ?v${fresh++} { 1 \"GRAPH\" } "
            8 -> "{ SELECT ?s (COUNT(*) AS ?n${fresh++}) ${pick("WHERE ", "")}{ ?s ex:p ?o FILTER(?o != 1) } GROUP BY ?s } "
            9, 10 -> "${graphKeyword()} ${pick("?g", "<urn:g1>", "ex:g", "?g2")} ${pick("{ ", "{", "\n{ ")}${elements(depth + 1, true)}} "
            11 -> "OPTIONAL { ${elements(depth + 1, false)}} "
            12 -> "{ ${triple()}${elements(depth + 1, false)}} UNION { ${triple()}${elements(depth + 1, false)}} "
            else -> {
                service = true
                "SERVICE ${pick("", "SILENT ")}<urn:ep> { ${triple()}} "
            }
        }

        fun query(): Generated {
            service = false
            val head = pick("SELECT *", "SELECT *", "SELECT ?s", "SELECT DISTINCT ?s ?o", "ASK", "CONSTRUCT { ?s ex:from ?o }", "DESCRIBE ?s")
            val dataset = if (!chance(25)) "" else pick(
                " ${fromKeyword()} <urn:g1>", " ${fromKeyword()} NAMED <urn:g2>", " from <urn:g1> ${fromKeyword()} named <urn:g2>",
            )
            val where = pick(" WHERE ", " ", "\nwhere ")
            val text = "PREFIX ex: <urn:ex:>\n" + (if (chance(30)) "# FROM <urn:c> GRAPH ?c { }\n" else "") +
                "$head$dataset$where{ ${triple()}${elements(0, false)}}${pick("", " LIMIT 5")}"
            return Generated(text, head == "SELECT *", service)
        }
    }

    private val seeds = listOf(20261002L, 1L, 42L, 8675309L, 314159265L)

    @TestFactory
    fun `the scanner and the GRAPH rewrite agree with RDF4J's parser on generated corpora`(): List<DynamicTest> = seeds.map { seed ->
        DynamicTest.dynamicTest("seed $seed") {
            val generator = Generator(seed)
            var withGraph = 0
            var withDataset = 0
            var escapedKeywords = 0
            var scopesCompared = 0
            repeat(150) {
                val generated = generator.query()
                val text = generated.text
                val parsed = parse(text)
                assertNotNull(parsed, "the generator produced a query RDF4J rejects (${parseError(text)}):\n$text")
                if (parsed.readsNamedGraphs) withGraph++
                if (parsed.dataset != null) withDataset++
                if (text.contains(u)) escapedKeywords++

                assertEquals(parsed.dataset != null, scan("declaresDataset", text), "FROM in:\n$text")
                assertEquals(parsed.readsNamedGraphs, scan("usesGraphPattern", text), "GRAPH in:\n$text")

                // The GRAPH rewrite: still SPARQL, no named graph read outside SERVICE, the same variables in scope.
                val rewritten = scan("withoutNamedGraphs", text) as String?
                assertNotNull(rewritten, "GRAPH rewrite of:\n$text")
                val reparsed = parse(rewritten)
                assertNotNull(reparsed, "the rewritten query is not SPARQL (${parseError(rewritten)}):\n$rewritten\nfrom:\n$text")
                if (!generated.hasService) assertFalse(reparsed.readsNamedGraphs, "GRAPH left in:\n$rewritten")
                assertEquals(parsed.dataset, reparsed.dataset, rewritten)
                if (generated.selectsAll) {
                    scopesCompared++
                    assertEquals(parsed.projected.toSet(), reparsed.projected.toSet(), "variables in scope of:\n$text\nrewritten:\n$rewritten")
                }

                // The dataset clauses: inserted where RDF4J reads them as the query's dataset.
                if (parsed.dataset == null) {
                    val inserted = scan("insert", text, "FROM <urn:inserted>") as String?
                    assertNotNull(inserted, "dataset clause insertion into:\n$text")
                    val withClauses = parse(inserted)
                    assertNotNull(withClauses, "the query with dataset clauses is not SPARQL (${parseError(inserted)}):\n$inserted")
                    assertTrue(withClauses.dataset.orEmpty().contains("FROM <urn:inserted>"), "dataset of:\n$inserted")
                    assertEquals(parsed.readsNamedGraphs, withClauses.readsNamedGraphs, inserted)
                }
            }
            // The corpus exercises what it is meant to.
            assertTrue(withGraph >= 25, "queries with GRAPH: $withGraph")
            assertTrue(withDataset >= 10, "queries with dataset clauses: $withDataset")
            assertTrue(escapedKeywords >= 10, "queries with a codepoint escape: $escapedKeywords")
            assertTrue(scopesCompared >= 15, "SELECT * queries whose scope was compared: $scopesCompared")
        }
    }
}
