package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
import org.apache.jena.query.QueryFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * A sandboxed query written from a template ([SparqlIriSubstitution], as the SHACL engine pre-binds a focus node) is
 * parsed once for every IRI written into it, gives the query its text denotes, and is checked as that text would be.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenaQueryTemplatesTest {

    private val repository: RdfRepository = JenaProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(
            Rdf.parse(
                (0 until 40).joinToString("\n") { "<urn:n$it> <urn:p> <urn:n${it + 1}> ; <urn:q> $it ." },
                RdfFormat.TURTLE,
            ).getTriples(),
        )
    }

    @AfterEach fun close() = repository.close()

    /** [template] with `?__t` written as `<iri>`, as the SHACL engine writes it. */
    private fun text(template: String, iri: String) = template.replace("?__t", "<$iri>")

    private fun substitution(template: String, iri: String) = SparqlIriSubstitution(template, mapOf("__t" to Iri(iri)))

    private fun rows(sparql: String, substitution: SparqlIriSubstitution?) =
        repository.withSelectRows(SandboxedSelectQuery(sparql, SparqlFunctionLibrary.EMPTY, substitution), emptyMap(), Duration.ofSeconds(30)) {
            it.map { row -> row.getVariableNames().sorted().associateWith { v -> row.get(v) } }.toList()
        }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT ?o WHERE { ?__t <urn:p> ?o }",
        "SELECT * WHERE { ?__t <urn:p> ?o }",
        "SELECT ?s WHERE { ?s <urn:p> ?__t }",
        "SELECT (?__t AS ?this) ?o WHERE { ?__t <urn:p> ?o }",
        "SELECT DISTINCT (?__t AS ?this) WHERE { ?__t ?p ?o }",
        "SELECT ?o WHERE { ?s <urn:p> ?o FILTER (?s = ?__t || sameTerm(?o, ?__t)) }",
        "SELECT ?o WHERE { ?s <urn:p> ?o FILTER NOT EXISTS { ?__t <urn:q> ?o } }",
        "SELECT ?v WHERE { { SELECT (?__t AS ?__kastor_prebound_0) ?v WHERE { ?__t <urn:q> ?v } } }",
        "SELECT ?n WHERE { { SELECT ?n WHERE { ?__t <urn:p>+ ?n } ORDER BY ?n LIMIT 3 } }",
        "SELECT (COUNT(?o) AS ?c) WHERE { ?__t (<urn:p>|^<urn:p>) ?o } GROUP BY ?o",
        "SELECT ?o WHERE { ?__t <urn:p>* ?o } ORDER BY (?__t) DESC(?o)",
        "SELECT ?o WHERE { OPTIONAL { ?__t <urn:q> ?o } }",
        "SELECT ?x WHERE { { ?__t <urn:q> ?x } UNION { ?x <urn:p> ?__t } }",
        "SELECT ?s WHERE { GRAPH ?g { ?s ?p ?o } FILTER (?g = ?__t) }",
        "SELECT ?l WHERE { ?__t <urn:q> ?v BIND (STR(?__t) AS ?l) }",
        "PREFIX u: <urn:> SELECT ?s WHERE { ?s u:p ?o VALUES ?o { u:n1 u:n2 } FILTER (?o != ?__t) }",
        "SELECT (SAMPLE(?__t) AS ?x) (GROUP_CONCAT(STR(?__t); SEPARATOR=\",\") AS ?y) WHERE { ?s <urn:q> ?v } HAVING (COUNT(?__t) > 0)",
        "BASE <http://example.org/dir/> SELECT ?o WHERE { ?__t <urn:p> ?o FILTER (?o != <rel>) }",
    ])
    fun `the substituted template is the query of the text`(template: String) {
        for (iri in listOf("urn:n3", "urn:n17", "http://example.org/a/b")) {
            val expected = QueryFactory.create(text(template, iri))
            val built = JenaQueryTemplates.substituted(substitution(template, iri)) { it }
            assertNotNull(built, template)
            assertEquals(expected, built, "the query of $template for $iri")
            assertEquals(rows(text(template, iri), null), rows(text(template, iri), substitution(template, iri)), template)
        }
    }

    @Test
    fun `a template is parsed once for every focus node`() {
        val template = "SELECT ?o WHERE { ?__t <urn:p> ?o FILTER (?o != <urn:parse-once>) }"
        JenaQueryTemplates.substituted(substitution(template, "urn:n0")) { it }
        val before = JenaQueryTemplates.parses
        val built = JenaQueryTemplates.substitutions
        for (k in 0 until 40) {
            assertEquals(listOf(mapOf("o" to Iri("urn:n${k + 1}"))), rows(text(template, "urn:n$k"), substitution(template, "urn:n$k")))
        }
        assertEquals(before, JenaQueryTemplates.parses, "template parses for 40 focus nodes")
        assertTrue(JenaQueryTemplates.substitutions - built >= 40, "queries built from the template")
    }

    @ParameterizedTest
    @ValueSource(strings = ["urn:a/../b", "http://example.org/a/./b", "http://example.org/a/../b", "http://example.org/."])
    fun `an IRI the parser would not keep as written falls back to the text`(iri: String) {
        val template = "SELECT ?o WHERE { ?__t <urn:p> ?o }"
        assertNull(JenaQueryTemplates.substituted(substitution(template, iri)) { it }, iri)
    }

    @Test
    fun `GROUP BY a substituted variable binds it, so the SHACL engine writes no template there`() {
        // The reason SparqlQueryTemplate keeps GROUP BY / ORDER BY slots out of its templates.
        val template = "SELECT (COUNT(?o) AS ?c) WHERE { ?__t <urn:p> ?o } GROUP BY (?__t)"
        val built = JenaQueryTemplates.substituted(substitution(template, "urn:n3")) { it }
        assertTrue(built.toString().contains("AS ?__t"), built.toString())
    }

    @Test
    fun `a template that does not parse leaves the text to report the error`() {
        val template = "SELECT ?o WHERE { ?__t <urn:p> ?o "
        assertNull(JenaQueryTemplates.substituted(substitution(template, "urn:n1")) { it })
        assertThrows(Exception::class.java) { rows(text(template, "urn:n1"), substitution(template, "urn:n1")) }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * WHERE { ?s ?__t ?o }",
        "SELECT * WHERE { SERVICE ?__t { ?s ?p ?o } }",
    ])
    fun `the substituted query is checked as its text is`(template: String) {
        for (iri in listOf("java:org.example.P", "http://jena.apache.org/ARQ/property#splitIRI", "http://127.0.0.1:1/s")) {
            val sparql = text(template, iri)
            val viaText = runCatching { rows(sparql, null) }.exceptionOrNull()
            val viaTemplate = runCatching { rows(sparql, substitution(template, iri)) }.exceptionOrNull()
            assertEquals(viaText?.javaClass, viaTemplate?.javaClass, "$template with <$iri>")
            if (iri.startsWith("java:") || template.contains("SERVICE")) {
                assertEquals(IllegalArgumentException::class.java, viaTemplate?.javaClass, "$template with <$iri>")
            }
        }
    }

    @Test
    fun `a shared template is not changed by concurrent executions`() {
        val template = "SELECT (?__t AS ?this) ?o WHERE { ?__t <urn:p> ?o OPTIONAL { ?o <urn:q> ?v } }"
        JenaQueryTemplates.substituted(substitution(template, "urn:n0")) { it }
        val before = QueryFactory.create(template).toString()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 240).map { k ->
                Callable { rows(text(template, "urn:n${k % 40}"), substitution(template, "urn:n${k % 40}")).single()["o"] }
            }
            pool.invokeAll(tasks).forEachIndexed { k, f -> assertEquals(Iri("urn:n${k % 40 + 1}"), f.get()) }
        } finally {
            pool.shutdownNow()
        }
        val shared = JenaQueryTemplates.substituted(SparqlIriSubstitution(template, emptyMap())) { it }
        assertEquals(before, shared.toString())
    }

    @Test
    fun `the cache is bounded`() {
        JenaQueryTemplates.clear()
        for (k in 0 until JenaQueryTemplates.MAX_ENTRIES + 20) {
            JenaQueryTemplates.substituted(substitution("SELECT ?o WHERE { ?__t <urn:p$k> ?o }", "urn:n1")) { it }
        }
        assertEquals(JenaQueryTemplates.MAX_ENTRIES, JenaQueryTemplates.size)
        val long = "SELECT ?o WHERE { ?__t <urn:p> ?o } #" + "x".repeat(JenaQueryTemplates.MAX_TEMPLATE_LENGTH)
        assertNull(JenaQueryTemplates.substituted(substitution(long, "urn:n1")) { it })
        assertEquals(JenaQueryTemplates.MAX_ENTRIES, JenaQueryTemplates.size)
    }
}
