package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Round seventeen, security review of the Jena UPDATE bound: every way an update computes a huge value in its `WHERE`
 * clause (a typed literal built from a long lexical form, by `STRDT` in any spelling, an XSD cast, `CALL`; a huge number
 * by `POWER`, `EXP10`, products, `ROUND`; a huge string), wherever the expression is (`EXISTS`, `NOT EXISTS`, nested,
 * sub-queries, `GROUP BY`, `HAVING`, `ORDER BY`), and a `LOAD` of a document with a million-digit literal, and
 * `SERVICE`, must be refused, or compute nothing, before the huge value is built. Each runs in a child JVM with a
 * 128 MiB heap and must end in under a second ([JenaUpdateBoundsProbe]).
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenaUpdateBoundsTest {

    @Test
    fun `huge values an update computes or loads are refused before they are built`() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val log = kotlin.io.path.createTempFile("kastor-update-probe", ".log").toFile().apply { deleteOnExit() }
        val process = ProcessBuilder(java, "-Xmx128m", "-cp", System.getProperty("java.class.path"), JenaUpdateBoundsProbe::class.java.name)
            .redirectErrorStream(true).redirectOutput(log).start()
        val ended = process.waitFor(200, TimeUnit.SECONDS)
        if (!ended) process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        val output = log.readText()
        assertTrue(ended, "the probe JVM did not end: $output")
        assertEquals(0, process.exitValue(), output)
        val lines = output.lines().filter { it.startsWith("PROBE\t") }
        println(lines.joinToString("\n"))
        assertEquals(JenaUpdateBoundsProbe.CASES.size, lines.size, output)
        for (line in lines) {
            val (_, name, outcome, millis, size) = line.split('\t')
            val case = JenaUpdateBoundsProbe.CASES.single { it.name == name }
            val accepted = if (case.mustRefuse) setOf("refused") else setOf("refused", "nothing")
            assertTrue(outcome in accepted, "$name: $line")
            assertTrue(millis.toLong() < 1_000, "$name took $millis ms: $line")
            assertEquals("0", size, "$name wrote (or kept) statements: $line")
        }
    }

    @Test
    fun `realistic updates still work`() {
        val xsd = "http://www.w3.org/2001/XMLSchema#"
        val doc = kotlin.io.path.createTempFile("kastor-load", ".ttl").toFile().apply {
            writeText("<http://e/a> <http://e/p> 42 , \"x\"@en .\n")
            deleteOnExit()
        }
        JenaRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery("INSERT DATA { <http://e/s> <http://e/n> 2 }"))
            repo.update(UpdateQuery("""
                PREFIX xsd: <$xsd>
                INSERT { <http://e/s> <http://e/m> ?m ; <http://e/t> ?t ; <http://e/d> ?d ; <http://e/u> ?u }
                WHERE { <http://e/s> <http://e/n> ?n BIND(?n * 21 AS ?m) BIND(STRDT("7", xsd:integer) AS ?t)
                        BIND(xsd:decimal("1.5") AS ?d) BIND(STRUUID() AS ?u) FILTER(?n > 1 && NOT EXISTS { <http://e/s> <http://e/x> ?n }) }
            """.trimIndent()))
            repo.update(UpdateQuery("DELETE { <http://e/s> <http://e/n> ?n } INSERT { <http://e/s> <http://e/n> ?k } WHERE { <http://e/s> <http://e/n> ?n BIND(?n + 1 AS ?k) }"))
            repo.update(UpdateQuery("INSERT DATA { GRAPH <http://e/g> { <http://e/x> <http://e/y> 1 } }"))
            repo.update(UpdateQuery("WITH <http://e/g> INSERT { <http://e/s> <http://e/c> ?c } WHERE { { SELECT (COUNT(*) AS ?c) WHERE { ?s ?p ?o } } }"))
            repo.update(UpdateQuery("LOAD <${doc.toURI()}>"))
            repo.update(UpdateQuery("LOAD <${doc.toURI()}> INTO GRAPH <http://e/loaded>"))
            repo.update(UpdateQuery("LOAD SILENT <${doc.toURI()}.missing>"))
            repo.update(UpdateQuery("DELETE WHERE { <http://e/a> <http://e/p> \"x\"@en }"))
            val default = repo.defaultGraph.getTriples().toSet()
            assertTrue(RdfTriple(Iri("http://e/s"), Iri("http://e/n"), TypedLiteral("3", Iri("${xsd}integer"))) in default, "$default")
            assertTrue(RdfTriple(Iri("http://e/s"), Iri("http://e/m"), TypedLiteral("42", Iri("${xsd}integer"))) in default, "$default")
            assertTrue(RdfTriple(Iri("http://e/a"), Iri("http://e/p"), TypedLiteral("42", Iri("${xsd}integer"))) in default, "$default")
            assertEquals(6, default.size, "$default")
            assertEquals(2, repo.getGraph(Iri("http://e/g")).size())
            assertEquals(2, repo.getGraph(Iri("http://e/loaded")).size())
        }
    }
}

/** The child JVM of [JenaUpdateBoundsTest]: runs each case on a fresh repository and prints its outcome and time. */
object JenaUpdateBoundsProbe {
    /** [mustRefuse]: the update must fail; otherwise it may also leave the variable unbound (an unknown function). */
    class Case(val name: String, val update: String, val mustRefuse: Boolean = true, val parse: Pair<String, java.io.File>? = null)

    private const val XSD = "http://www.w3.org/2001/XMLSchema#"
    private const val MATH = "http://www.w3.org/2005/xpath-functions/math#"

    /** `?a17`: 1,310,720 characters `1`, by 17 doublings of ten (2.6 MB of computed text in all). */
    private val DIGITS = "BIND(\"1111111111\" AS ?a0) " + (1..17).joinToString(" ") { "BIND(CONCAT(?a${it - 1}, ?a${it - 1}) AS ?a$it)" }
    private fun insert(where: String, graph: String? = null) =
        "PREFIX xsd: <$XSD> INSERT { ${graph?.let { "GRAPH <$it> { <http://e/s> <http://e/q> ?v }" } ?: "<http://e/s> <http://e/q> ?v"} } WHERE { $where }"
    private fun strdt(type: String = "xsd:integer") = "BIND(STRDT(?a17, $type) AS ?v)"

    private val bigLiteral = kotlin.io.path.createTempFile("kastor-big", ".ttl").toFile().apply {
        writeText("<http://e/s> <http://e/p> ${"\u0037".repeat(1_000_000)} .\n")
        deleteOnExit()
    }
    private fun bigFile(suffix: String, content: String) = kotlin.io.path.createTempFile("kastor-big", suffix).toFile().apply {
        writeText(content)
        deleteOnExit()
    }
    private val million = "7".repeat(1_000_000)
    private val nl = "\n"
    private val escapes = (Char(92) + "u0037").repeat(1_000_000)
    private val bigFormats = listOf(
        "N-Triples" to bigFile(".nt", "<http://e/s> <http://e/p> \"$million\"^^<${XSD}integer> .$nl"),
        "N-Quads" to bigFile(".nq", "<http://e/s> <http://e/p> \"$million\"^^<${XSD}decimal> <http://e/g> .$nl"),
        "TriG" to bigFile(".trig", "PREFIX xsd: <$XSD>$nl<http://e/g> { <http://e/s> <http://e/p> \"$million\"^^xsd:integer }$nl"),
        "Turtle escapes" to bigFile(".ttl", "<http://e/s> <http://e/p> \"$escapes\"^^<${XSD}integer> .$nl"),
        "Turtle dateTime" to bigFile(".ttl", "<http://e/s> <http://e/p> \"2020-01-01T00:00:00.$million\"^^<${XSD}dateTime> .$nl"),
        "RDF/XML" to bigFile(".rdf", rdfXml(million)),
        "RDF/XML character references" to bigFile(".rdf", rdfXml("&#55;".repeat(1_000_000))),
    )
    private fun rdfXml(value: String) = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:e=\"http://e/\">" +
        "<rdf:Description rdf:about=\"http://e/s\"><e:p rdf:datatype=\"${XSD}integer\">$value</e:p></rdf:Description></rdf:RDF>"
    private val bigJsonLd = kotlin.io.path.createTempFile("kastor-big", ".jsonld").toFile().apply {
        writeText("""{"@id": "http://e/s", "http://e/p": {"@value": "${"\u0037".repeat(1_000_000)}", "@type": "${XSD}integer"}}""")
        deleteOnExit()
    }
    private val baseNull = kotlin.io.path.createTempFile("kastor-basenull", ".jsonld").toFile().apply {
        writeText("""{"@context": {"@base": null}, "@id": "s", "http://e/p": {"@id": "o"}}""")
        deleteOnExit()
    }

    val CASES = listOf(
        Case("STRDT", insert("$DIGITS ${strdt()}")),
        Case("STRDT decimal", insert("$DIGITS BIND(STRDT(CONCAT(?a17, \".5\"), xsd:decimal) AS ?v)")),
        Case("STRDT dateTime", insert("$DIGITS BIND(STRDT(CONCAT(\"2020-01-01T00:00:00.\", ?a17), xsd:dateTime) AS ?v)")),
        Case("STRDT computed datatype", insert("$DIGITS BIND(xsd:integer AS ?dt) BIND(STRDT(?a17, ?dt) AS ?v)")),
        Case("STRDT through IF", insert("$DIGITS BIND(STRDT(IF(true, ?a17, \"1\"), xsd:integer) AS ?v)")),
        Case("STRDT through COALESCE", insert("$DIGITS BIND(STRDT(COALESCE(?unbound, ?a17), xsd:integer) AS ?v)")),
        Case("sparql:strdt function", insert("$DIGITS BIND(<http://www.w3.org/ns/sparql#strdt>(?a17, xsd:integer) AS ?v)"), mustRefuse = false),
        Case("cast integer", insert("$DIGITS BIND(xsd:integer(?a17) AS ?v)")),
        Case("cast decimal", insert("$DIGITS BIND(xsd:decimal(CONCAT(?a17, \".5\")) AS ?v)")),
        Case("cast long", insert("$DIGITS BIND(xsd:long(?a17) AS ?v)")),
        Case("cast dateTime", insert("$DIGITS BIND(xsd:dateTime(CONCAT(\"2020-01-01T00:00:00.\", ?a17)) AS ?v)")),
        Case("cast duration", insert("$DIGITS BIND(xsd:duration(CONCAT(\"PT\", ?a17, \"S\")) AS ?v)")),
        Case("cast of a cast", insert("$DIGITS BIND(xsd:integer(xsd:string(?a17)) AS ?v)")),
        Case("CALL cast", insert("$DIGITS BIND(CALL(xsd:integer, ?a17) AS ?v)")),
        Case("CALL computed IRI", insert("$DIGITS BIND(CALL(IRI(CONCAT(STR(xsd:), \"integer\")), ?a17) AS ?v)")),
        Case("POWER", insert("BIND(<${MATH}pow>(10, 1000000) AS ?v)")),
        Case("EXP10", insert("BIND(<${MATH}exp10>(1000000) AS ?v)")),
        Case("squaring", insert("BIND(12345678901234567890 AS ?m0) " + (1..24).joinToString(" ") { "BIND(?m${it - 1} * ?m${it - 1} AS ?m$it)" } + " BIND(?m24 AS ?v)")),
        Case("ROUND precision", insert("BIND(<http://www.w3.org/2005/xpath-functions#round-half-to-even>(1.5, 2147483647) AS ?v)")),
        Case("CONCAT growth", insert("$DIGITS " + (18..27).joinToString(" ") { "BIND(CONCAT(?a${it - 1}, ?a${it - 1}) AS ?a$it)" } + " BIND(STRLEN(?a27) AS ?v)")),
        Case("REPLACE growth", insert("$DIGITS BIND(REPLACE(?a17, \"1\", \"1111111111111111\") AS ?b) BIND(REPLACE(?b, \"1\", \"1111111111111111\") AS ?v)")),
        Case("UCASE over the budget", insert("$DIGITS " + (18..23).joinToString(" ") { "BIND(CONCAT(?a${it - 1}, ?a${it - 1}) AS ?a$it)" } + " BIND(UCASE(?a23) AS ?b) BIND(LCASE(?b) AS ?c) BIND(UCASE(?c) AS ?v)")),
        Case("FILTER", insert("$DIGITS FILTER(STRDT(?a17, xsd:integer) > 0) BIND(1 AS ?v)")),
        Case("FILTER EXISTS", insert("$DIGITS FILTER EXISTS { ${strdt()} } BIND(1 AS ?v)")),
        Case("FILTER NOT EXISTS", insert("$DIGITS FILTER NOT EXISTS { ${strdt()} } BIND(1 AS ?v)")),
        Case("EXISTS in EXISTS", insert("$DIGITS FILTER EXISTS { FILTER EXISTS { ${strdt()} } } BIND(1 AS ?v)")),
        Case("EXISTS in BIND", insert("$DIGITS BIND(EXISTS { ${strdt()} } AS ?v)")),
        Case("EXISTS in a sub-SELECT in GRAPH", insert("GRAPH <http://e/g> { { SELECT ?v WHERE { $DIGITS FILTER EXISTS { ${strdt()} } BIND(1 AS ?v) } } }")),
        Case("sub-SELECT", insert("{ SELECT ?v WHERE { $DIGITS ${strdt()} } }")),
        Case("GROUP BY", insert("{ SELECT ?v WHERE { $DIGITS } GROUP BY (STRDT(?a17, xsd:integer) AS ?v) }")),
        Case("HAVING", insert("{ SELECT (COUNT(*) AS ?v) WHERE { $DIGITS } GROUP BY ?a17 HAVING (STRDT(?a17, xsd:integer) > 0) }")),
        Case("ORDER BY", insert("{ SELECT ?v WHERE { $DIGITS VALUES ?v { 1 2 } } ORDER BY (STRDT(CONCAT(?a17, STR(?v)), xsd:integer)) }")),
        Case("ORDER BY LIMIT", insert("{ SELECT ?v WHERE { $DIGITS VALUES ?v { 1 2 } } ORDER BY (STRDT(CONCAT(?a17, STR(?v)), xsd:integer)) LIMIT 1 }")),
        Case("aggregate", insert("{ SELECT (MAX(STRDT(?a17, xsd:integer)) AS ?v) WHERE { $DIGITS } }")),
        Case("named graph template", insert("$DIGITS ${strdt("xsd:long")}", graph = "http://e/g")),
        Case("DELETE/INSERT", "PREFIX xsd: <$XSD> DELETE { <http://e/s> <http://e/q> ?v } INSERT { <http://e/s> <http://e/r> ?v } WHERE { $DIGITS ${strdt()} }"),
        Case("VALUES", "INSERT { <http://e/s> <http://e/q> ?v } WHERE { VALUES ?v { \"${"\u0037".repeat(1_000_000)}\"^^<${XSD}integer> } }"),
        Case("SERVICE", insert("SERVICE <http://127.0.0.1:9/sparql> { ?s ?p ?v }")),
        // Refused before evaluation, not skipped: the run-time registries alone would make these silent no-ops.
        Case("SERVICE SILENT", insert("SERVICE SILENT <http://127.0.0.1:9/sparql> { ?s ?p ?v }")),
        Case("ARQ extension function", insert("BIND(<http://jena.apache.org/ARQ/function#sha1sum>(\"x\") AS ?v)")),
        Case("LOAD", "LOAD <${bigLiteral.toURI()}>"),
        Case("LOAD INTO GRAPH", "LOAD <${bigLiteral.toURI()}> INTO GRAPH <http://e/g>"),
        Case("LOAD JSON-LD", "LOAD <${bigJsonLd.toURI()}>"),
        Case("LOAD JSON-LD @base null", "LOAD <${baseNull.toURI()}>"),
    ) + bigFormats.map { (format, file) -> Case("LOAD $format", "LOAD <${file.toURI()}>") } +
        // The parse API reads with the same bounded parser as LOAD.
        (bigFormats + ("JSON-LD" to bigJsonLd)).map { (format, file) -> Case("parseDataset $format", "", parse = formatOf(file) to file) }

    private fun formatOf(file: java.io.File) = when (file.extension) {
        "nt" -> "N-TRIPLES"; "nq" -> "N-QUADS"; "trig" -> "TRIG"; "ttl" -> "TURTLE"; "rdf" -> "RDF/XML"; else -> "JSON-LD"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
        // Warm up the engine on a realistic update, so the first case does not pay for class loading.
        JenaRepository.MemoryRepository().use { repo ->
            repeat(3) { repo.update(UpdateQuery(insert("BIND(STRDT(CONCAT(\"1\", \"2\"), xsd:integer) AS ?v) FILTER EXISTS { BIND(1 AS ?x) }"))) }
        }
        for (case in CASES) {
            JenaRepository.MemoryRepository().use { repo ->
                // One statement in <http://e/g>, so that the patterns over that graph have something to match.
                repo.update(UpdateQuery("INSERT DATA { GRAPH <http://e/g> { <http://e/x> <http://e/y> 1 } }"))
                val started = System.nanoTime()
                val outcome = try {
                    case.parse?.let { (format, file) -> file.inputStream().use { JenaProvider().parseDataset(repo, it, format, null) } }
                        ?: repo.update(UpdateQuery(case.update))
                    if (repo.defaultGraph.size() == 0 && repo.getGraph(Iri("http://e/g")).size() == 1 && repo.listGraphs().size == 1) "nothing" else "written"
                } catch (e: RdfQueryException) {
                    "refused"
                } catch (e: RdfFormatException) {
                    "refused"
                } catch (e: Throwable) {
                    "error ${e.javaClass.name}: ${e.message?.take(200)}"
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                val size = repo.defaultGraph.size() + repo.listGraphs().sumOf { repo.getGraph(it).size() } - 1
                println("PROBE\t${case.name}\t$outcome\t$millis\t$size")
            }
        }
    }
}
