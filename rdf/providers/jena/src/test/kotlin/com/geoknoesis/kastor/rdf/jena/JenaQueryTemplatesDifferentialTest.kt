package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
import org.apache.jena.query.QueryFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Differential: every query written from a template ([SparqlIriSubstitution]) gives exactly what its text gives (the
 * same rows, or the same error), wherever the substituted variable stands: triple patterns, triple terms and reifier
 * patterns (in patterns and in expressions), EXISTS / NOT EXISTS, sub-selects, property paths, VALUES, BIND, filters,
 * function arguments, aggregates, projections, and combinations of them; for IRIs that are awkward to write too.
 * A query the cache builds is also the query of the text.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenaQueryTemplatesDifferentialTest {

    private val repository: RdfRepository = JenaProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(Rdf.parse(DATA, RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    /** [template] with its `?__t` / `\$__t` variable tokens (never one inside a string) written `<iri>`, as the SHACL engine writes it. */
    private fun text(template: String, iri: String): String = buildString {
        var quoted = false
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '?' || c == '$') && template.startsWith("__t", i + 1)) {
                append('<').append(iri).append('>')
                i += 4
            } else {
                append(c)
                i++
            }
        }
    }

    private fun substitution(template: String, iri: String) = SparqlIriSubstitution(template, mapOf("__t" to unchecked(iri)))

    private fun outcome(sparql: String, substitution: SparqlIriSubstitution?, library: SparqlFunctionLibrary = SparqlFunctionLibrary.EMPTY): String =
        try {
            repository.withSelectRows(SandboxedSelectQuery(sparql, library, substitution), emptyMap(), Duration.ofSeconds(30)) { rows ->
                rows.map { row -> row.getVariableNames().sorted().associateWith { v -> row.get(v).toString() }.toString() }.toList().sorted().toString()
            }
        } catch (e: Exception) {
            "error ${e.javaClass.name}: ${e.message}"
        }

    @Test
    fun `the template path gives what the text gives, for every query shape and IRI`() {
        val differences = ArrayList<String>()
        var built = 0
        for (template in QUERIES) for (iri in IRIS) {
            val sparql = text(template, iri)
            val viaText = outcome(sparql, null)
            val viaTemplate = outcome(sparql, substitution(template, iri))
            if (viaText != viaTemplate) differences += "$template with <$iri>\n  text:     $viaText\n  template: $viaTemplate"
            val query = runCatching { JenaQueryTemplates.substituted(substitution(template, iri)) { it } }.getOrNull()
            if (query != null) {
                built++
                val expected = runCatching { QueryFactory.create(sparql) }.getOrNull()
                if (expected != query || expected.toString() != query.toString()) differences += "$template with <$iri>: built ${query.toString().trim()}"
            }
        }
        assertEquals(emptyList<String>(), differences, differences.joinToString("\n"))
        assertTrue(built > QUERIES.size, "queries built from templates: $built")
    }

    @Test
    fun `templates with the variable in triple terms, reifier patterns and EXISTS are used, not only refused`() {
        val unused = QUERIES.filter { JenaQueryTemplates.substituted(substitution(it, "urn:n1")) { t -> t } == null }
        println("templates parsed as text every time:\n" + unused.joinToString("\n"))
        val expectedUnused = setOf(
            "SELECT (<<( ?__t <urn:p> <urn:n2> )>> AS ?x) WHERE { }",
            "SELECT ?x WHERE { ?__t <urn:p> ?o BIND(\"?__t\" AS ?x) }",
            "SELECT ?o WHERE { ?__t <urn:p> ?o ",
            "SELECT ?o WHERE { ?__t <urn:p> ?o BIND(1 AS ?__t) }",
        )
        assertEquals(expectedUnused, unused.toSet())
    }

    @Test
    fun `executions sharing a template never share a function bound by another execution`() {
        val template = "SELECT ?v WHERE { ?__t <urn:p> ?o BIND(<urn:fn>() AS ?v) }"
        fun library(answer: String) = SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:fn", emptyList()) { Literal(answer) }))
        val sparql = text(template, "urn:n1")
        val viaText = outcome(sparql, null, library("b"))
        outcome(sparql, substitution(template, "urn:n1"), library("a"))
        assertEquals(viaText, outcome(sparql, substitution(template, "urn:n1"), library("b")))
        assertTrue(viaText.contains("\"b\""), viaText)
    }

    @Test
    fun `concurrent executions of one template with different libraries and IRIs stay independent`() {
        val template = "SELECT ?v ?o WHERE { ?__t <urn:p> ?o FILTER(<urn:fn>() != \"\") BIND(<urn:fn>() AS ?v) " +
            "FILTER NOT EXISTS { ?__t <urn:says> <<( ?__t <urn:p> ?o )>> } }"
        val libraries = (0 until 4).map { k -> SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:fn", emptyList()) { Literal("l$k") })) }
        val iris = listOf("urn:n1", "urn:n2", "urn:n3", "http://ex/a")
        val expected = HashMap<Pair<Int, String>, String>()
        for (k in libraries.indices) for (iri in iris) expected[k to iri] = outcome(text(template, iri), null, libraries[k])
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 800).map { n ->
                val k = n % libraries.size
                val iri = iris[(n / libraries.size) % iris.size]
                Callable { Triple(k, iri, outcome(text(template, iri), substitution(template, iri), libraries[k])) }
            }
            val mismatches = pool.invokeAll(tasks).map { it.get() }.filter { (k, iri, got) -> got != expected[k to iri] }
            assertEquals(emptyList<Any>(), mismatches)
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        /** [iri] as an [Iri] without the RFC 3987 check of its constructor: data IRIs reach a query unchecked. */
        fun unchecked(iri: String): Iri = Iri::class.java.getMethod("box-impl", String::class.java).invoke(null, iri) as Iri

        val DATA = """
            <urn:n1> <urn:p> <urn:n2> ; <urn:q> 1 .
            <urn:n2> <urn:p> <urn:n3> ; <urn:q> 2 .
            <urn:n3> <urn:p> <urn:n4> ; <urn:q> 3 .
            <http://ex/a> <urn:p> <urn:n1> ; <urn:q> 4 .
            <http://ex/b> <urn:p> <urn:n1> .
            <urn:r1> <urn:says> <<( <urn:n1> <urn:p> <urn:n2> )>> .
            <urn:r2> <urn:says> <<( <http://ex/a> <urn:p> <urn:n1> )>> .
            <urn:r3> <urn:says> <<( <urn:x> <urn:p> <<( <urn:n1> <urn:p> <urn:n2> )>> )>> .
            <urn:n1> <urn:says> <<( <urn:n1> <urn:p> <urn:n2> )>> .
            << <urn:n2> <urn:p> <urn:n3> >> <urn:src> <urn:s1> .
            <urn:n3> <urn:p> <urn:n4> ~ <urn:reif> {| <urn:src> <urn:s2> |} .
        """.trimIndent()

        val IRIS = listOf(
            "urn:n1", "urn:n2", "urn:n3", "urn:zzz", "http://ex/a", "http://ex/%41", "http://ex/\u00e9", "http://ex:xyz/a",
            "http://[bad/a", "mailto:a@b", "http://ex/a#b#c", "http://ex/?__t", "x:", "urn:a/../b", "http://ex/a/./b",
            "_:b0", "urn:a'b", "urn:a#?__t)", "urn:\u0301", "http://ex/a?q=1&r=2",
        )

        val QUERIES = listOf(
            // Triple patterns, positions, projections
            "SELECT ?o WHERE { ?__t <urn:p> ?o }",
            "SELECT * WHERE { ?__t <urn:p> ?o }",
            "SELECT ?s WHERE { ?s <urn:p> ?__t }",
            "SELECT ?o WHERE { ?__t ?__t ?o }",
            "SELECT (?__t AS ?this) ?o WHERE { ?__t <urn:p> ?o }",
            "SELECT DISTINCT (?__t AS ?this) WHERE { ?__t ?p ?o }",
            "SELECT (1 AS ?__kastor_ask) WHERE { ?__t <urn:p> ?o }",
            "SELECT ?o WHERE { ?__t <urn:p> [ <urn:p> ?o ] }",
            "SELECT ?o WHERE { ?__t <urn:p> ?o , ?o2 ; <urn:q> ?v }",
            // Triple terms and reifier patterns
            "SELECT ?r WHERE { ?r <urn:says> <<( ?__t <urn:p> ?o )>> }",
            "SELECT ?r WHERE { ?r <urn:says> <<( ?__t <urn:p> <urn:n2> )>> }",
            "SELECT ?r WHERE { ?r <urn:says> <<( ?s <urn:p> ?__t )>> }",
            "SELECT ?r WHERE { ?r <urn:says> <<( <urn:x> <urn:p> <<( ?__t <urn:p> ?o )>> )>> }",
            "SELECT ?s WHERE { << ?__t <urn:p> ?o >> <urn:src> ?s }",
            "SELECT ?s WHERE { << ?x <urn:p> ?__t >> <urn:src> ?s }",
            "SELECT ?s ?r WHERE { << ?__t <urn:p> ?o ~ ?r >> <urn:src> ?s }",
            "SELECT ?s WHERE { ?__t <urn:p> ?o {| <urn:src> ?s |} }",
            "SELECT ?s WHERE { <<( ?__t <urn:p> ?o )>> ?pp ?s }",
            "SELECT ?s WHERE { ?s ?p ?o . ?s <urn:says> <<( ?__t ?pp ?oo )>> }",
            "SELECT ?s WHERE { ?__t <urn:says> <<( ?__t <urn:p> ?o )>> . BIND(?__t AS ?s) }",
            // Triple terms in expressions
            "SELECT ?r WHERE { ?r <urn:says> ?tt FILTER(?tt = <<( ?__t <urn:p> <urn:n2> )>>) }",
            "SELECT ?x WHERE { BIND(<<( ?__t <urn:p> <urn:n2> )>> AS ?x) }",
            "SELECT ?x WHERE { BIND(TRIPLE(?__t, <urn:p>, <urn:n2>) AS ?x) }",
            "SELECT ?r WHERE { ?r <urn:says> ?tt FILTER(SUBJECT(?tt) = ?__t) }",
            "SELECT ?r WHERE { ?r <urn:says> ?tt FILTER(sameTerm(?tt, <<( ?__t <urn:p> ?o )>>)) }",
            "SELECT ?r WHERE { ?r <urn:says> ?tt FILTER(?tt IN (<<( ?__t <urn:p> <urn:n2> )>>, <<( <urn:n9> <urn:p> <urn:n9> )>>)) }",
            "SELECT ?x WHERE { BIND(isTRIPLE(<<( ?__t <urn:p> <urn:n2> )>>) AS ?x) }",
            "SELECT (<<( ?__t <urn:p> <urn:n2> )>> AS ?x) WHERE { }",
            "SELECT ?r WHERE { ?r <urn:says> ?tt } ORDER BY (?tt = <<( ?__t <urn:p> <urn:n2> )>>) ?r",
            "SELECT ?r (COUNT(*) AS ?c) WHERE { ?r <urn:says> ?tt } GROUP BY ?r HAVING (SAMPLE(?tt) != <<( ?__t <urn:p> <urn:n2> )>>)",
            // EXISTS / NOT EXISTS, also with triple terms inside
            "SELECT ?o WHERE { ?s <urn:p> ?o FILTER NOT EXISTS { ?__t <urn:q> ?o } }",
            "SELECT ?b WHERE { BIND(EXISTS { ?__t <urn:p> ?x } AS ?b) }",
            "SELECT ?s WHERE { ?s <urn:says> ?t . FILTER EXISTS { ?s <urn:says> <<( ?__t <urn:p> ?z )>> } }",
            "SELECT ?x WHERE { BIND(<urn:x> AS ?x) FILTER NOT EXISTS { ?r <urn:says> <<( ?__t <urn:p> ?o )>> } }",
            "SELECT ?x WHERE { BIND(<urn:x> AS ?x) FILTER NOT EXISTS { << ?__t <urn:p> ?o >> <urn:src> ?s } }",
            "SELECT ?x WHERE { BIND(<urn:x> AS ?x) FILTER EXISTS { ?r <urn:says> ?tt FILTER EXISTS { ?r <urn:says> <<( ?__t <urn:p> ?o )>> } } }",
            "SELECT ?x WHERE { BIND(<urn:x> AS ?x) FILTER(EXISTS { ?__t <urn:p> ?o } && NOT EXISTS { ?r <urn:says> <<( ?__t <urn:p> ?o )>> }) }",
            "SELECT ?o WHERE { ?__t <urn:p> ?o FILTER NOT EXISTS { SELECT ?o WHERE { ?__t <urn:q> ?o } } }",
            // Sub-selects, LATERAL, MINUS, OPTIONAL, UNION, GRAPH
            "SELECT ?o WHERE { { SELECT * WHERE { ?__t <urn:p> ?o } } }",
            "SELECT * WHERE { ?s <urn:p> ?o { SELECT * WHERE { ?__t <urn:q> ?v } } }",
            "SELECT ?v WHERE { { SELECT (?__t AS ?__kastor_prebound_0) ?v WHERE { ?__t <urn:q> ?v } } }",
            "SELECT ?r WHERE { { SELECT ?r WHERE { ?r <urn:says> <<( ?__t <urn:p> ?o )>> } } }",
            "SELECT ?r WHERE { { SELECT ?r WHERE { ?r <urn:says> ?tt FILTER(?tt = <<( ?__t <urn:p> <urn:n2> )>>) } } }",
            "SELECT ?o WHERE { LATERAL { SELECT ?o WHERE { ?__t <urn:p> ?o } LIMIT 1 } }",
            "SELECT ?o WHERE { { ?__t <urn:p> ?o } MINUS { ?__t <urn:q> ?o } }",
            "SELECT ?o WHERE { OPTIONAL { ?__t <urn:q> ?o } FILTER(!BOUND(?o)) }",
            "SELECT ?x WHERE { { ?__t <urn:q> ?x } UNION { ?x <urn:p> ?__t } UNION { ?x <urn:says> <<( ?__t <urn:p> ?y )>> } }",
            "SELECT ?o WHERE { GRAPH ?g { ?__t <urn:p> ?o } }",
            "SELECT ?o WHERE { GRAPH ?__t { ?s <urn:p> ?o } }",
            "SELECT ?s WHERE { GRAPH ?g { ?s ?p ?o } FILTER (?g = ?__t) }",
            // Property paths
            "SELECT ?o WHERE { ?__t <urn:p>/<urn:p> ?o }",
            "SELECT ?o WHERE { ?o ^<urn:p> ?__t }",
            "SELECT ?o WHERE { ?__t !(<urn:q>) ?o }",
            "SELECT ?o WHERE { ?__t <urn:p>* ?o }",
            "SELECT ?o WHERE { ?__t (<urn:p>|^<urn:p>)+ ?o }",
            "SELECT ?o WHERE { ?__t <urn:p>? ?o }",
            // VALUES, BIND, filters, function arguments
            "SELECT ?o WHERE { ?__t <urn:p> ?o } VALUES ?o { <urn:n2> <urn:n1> }",
            "SELECT ?o WHERE { VALUES ?o { <urn:n2> <urn:n3> } ?__t <urn:p> ?o }",
            "SELECT ?o WHERE { ?s ?p ?o FILTER(?s IN (?__t, <urn:n2>)) }",
            "SELECT ?o WHERE { ?s ?p ?o FILTER(?s NOT IN (?__t)) }",
            "SELECT ?l WHERE { BIND(IRI(STR(?__t)) AS ?l) BIND(STRLEN(STR(?__t)) AS ?n) }",
            "SELECT ?o WHERE { BIND(?__t AS ?o) FILTER(isIRI(?o)) }",
            "SELECT ?x WHERE { BIND(IF(?__t = <urn:n1>, \"y\", \"n\") AS ?x) }",
            "SELECT ?x WHERE { BIND(COALESCE(?unbound, ?__t) AS ?x) }",
            "SELECT ?x WHERE { BIND(CONCAT(STR(?__t), \"-\", REPLACE(STR(?__t), \"n\", \"m\")) AS ?x) }",
            "SELECT ?x WHERE { BIND(REGEX(STR(?__t), \"^urn\") AS ?x) }",
            "SELECT ?x WHERE { BIND(<http://www.w3.org/2001/XMLSchema#string>(?__t) AS ?x) }",
            "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(sameTerm(?s, ?__t)) } LIMIT 1 OFFSET 0",
            "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(?__t != ?o && STR(?__t) < STR(?o)) }",
            "SELECT ?r ?x WHERE { ?r <urn:says> ?tt BIND(isTRIPLE(?tt) AS ?x) FILTER(OBJECT(?tt) = ?__t || SUBJECT(?tt) = ?__t) }",
            // Aggregates, grouping, ordering
            "SELECT (COUNT(DISTINCT ?__t) AS ?c) WHERE { ?s ?p ?o }",
            "SELECT ?o (SAMPLE(?__t) AS ?x) WHERE { ?s <urn:p> ?o } GROUP BY ?o",
            "SELECT ?s (COUNT(?o) AS ?c) WHERE { ?s <urn:p> ?o } GROUP BY ?s HAVING (?s = ?__t)",
            "SELECT (GROUP_CONCAT(?s; SEPARATOR=\" \") AS ?g) WHERE { ?s <urn:p> ?o FILTER(?o != ?__t) }",
            "SELECT (COUNT(?o) AS ?c) WHERE { ?__t (<urn:p>|^<urn:p>) ?o } GROUP BY ?o",
            "SELECT ?x WHERE { ?__t <urn:p> ?x } ORDER BY ?x LIMIT 1 OFFSET 1",
            "SELECT (?__t AS ?this) ?o WHERE { ?s <urn:p> ?o } ORDER BY ?o",
            "SELECT ?o WHERE { ?s <urn:p> ?o } ORDER BY DESC(?o = ?__t) ?o",
            "SELECT (MAX(STR(?__t)) AS ?m) WHERE { ?s <urn:p> ?o }",
            // The placeholder spelled in strings and comments, or with the other sigil
            "SELECT ?x WHERE { ?__t <urn:p> ?o BIND(\"?__t\" AS ?x) }",
            "SELECT ?o WHERE { ?__t <urn:p> ?o } # ?__t in a comment",
            "SELECT ?o WHERE { \$__t <urn:p> ?o }",
            // Templates that do not parse, that assign the variable, or that the sandbox refuses
            "SELECT ?o WHERE { ?__t <urn:p> ?o ",
            "SELECT * WHERE { SERVICE ?__t { ?s ?p ?o } }",
            "SELECT ?o WHERE { ?__t <urn:p> ?o BIND(1 AS ?__t) }",
            "BASE <http://example.org/dir/> SELECT ?o WHERE { ?__t <urn:p> ?o FILTER (?o != <rel>) }",
        )
    }
}
