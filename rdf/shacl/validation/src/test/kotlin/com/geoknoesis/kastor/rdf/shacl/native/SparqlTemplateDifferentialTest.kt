package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration

/**
 * Differential for the query template a bound SHACL-SPARQL query offers its provider ([SparqlQueryTemplate.bind],
 * [SparqlIriSubstitution]): for `$this` in many syntactic positions and focus nodes of every kind (IRIs, awkward IRIs,
 * blank nodes, literals, triple terms), the query run on Jena with the template gives exactly what Jena gives for the
 * text alone (the same rows or the same error), and what RDF4J gives for the text wherever both answer.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SparqlTemplateDifferentialTest {

    private val d = "$"
    private val jena: RdfRepository = JenaProvider().createRepository("memory", RdfConfig()).apply { load(this) }
    private val rdf4j: RdfRepository = Rdf4jProvider().createRepository("memory", RdfConfig()).apply { load(this) }

    @AfterEach fun close() {
        jena.close()
        rdf4j.close()
    }

    private fun load(repository: RdfRepository) = repository.editDefaultGraph().addTriples(Rdf.parse(DATA, RdfFormat.TURTLE).getTriples())

    /** The data's blank node marked [kind], as [repository] names it. */
    private fun blank(repository: RdfRepository, kind: String): RdfTerm =
        repository.select(SandboxedSelectQuery("SELECT ?b WHERE { ?b <urn:kind> \"$kind\" }")).single().get("b")!!

    private fun outcome(repository: RdfRepository, bound: SparqlQueryTemplate.Bound, substitution: SparqlIriSubstitution?): String =
        try {
            repository.withSelectRows(SandboxedSelectQuery(bound.text, SparqlFunctionLibrary.EMPTY, substitution), bound.providerBindings, Duration.ofSeconds(30)) { rows ->
                rows.map { row -> row.getVariableNames().sorted().associateWith { v -> render(row.get(v)) }.toString() }.toList().sorted().toString()
            }
        } catch (e: Exception) {
            "error ${e.javaClass.name}: ${e.message}"
        }

    /** Blank nodes as `_:` (each provider labels them its own way), every other term as it prints. */
    private fun render(term: RdfTerm?): String = when (term) {
        is BlankNode -> "_:"
        is TripleTerm -> "<<( ${render(term.triple.subject)} ${term.triple.predicate} ${render(term.triple.obj)} )>>"
        else -> term.toString()
    }

    private fun foci(repository: RdfRepository): List<Pair<String, RdfTerm>> = listOf(
        "urn:n1" to Iri("urn:n1"), "urn:n2" to Iri("urn:n2"), "urn:zzz" to Iri("urn:zzz"), "http://ex/a" to Iri("http://ex/a"),
        "%41" to unchecked("http://ex/%41"), "e-acute" to unchecked("http://ex/\u00e9"), "x:" to unchecked("x:"),
        "dot-segments" to unchecked("http://ex/a/../b"), "bnode-iri" to unchecked("_:b0"), "quote" to unchecked("urn:a'b"),
        "space" to unchecked("urn:a b"), "angle" to unchecked("urn:a>b"), "placeholder" to unchecked("urn:?__kastor_iri_this"),
        "blank" to blank(repository, "blank"), "reified-blank" to blank(repository, "reified"),
        "literal" to com.geoknoesis.kastor.rdf.string("n1"), "integer" to com.geoknoesis.kastor.rdf.Literal(1),
        "lang" to com.geoknoesis.kastor.rdf.LangString("chat", "fr"),
        "triple-term" to TripleTerm(com.geoknoesis.kastor.rdf.RdfTriple(Iri("urn:n1"), Iri("urn:p"), Iri("urn:n2"))),
    )

    @Test
    fun `the template a bound query offers gives what its text gives, on Jena and against RDF4J`() {
        val templateDifferences = ArrayList<String>()
        val providerDifferences = ArrayList<String>()
        var offered = 0
        val jenaFoci = foci(jena)
        val rdf4jFoci = foci(rdf4j).toMap()
        for (body in QUERIES) {
            val template = try {
                SparqlQueryTemplate.compileRestricted("", body.replace("\$this", "${d}this"), setOf("this"), "the test query")
            } catch (e: Exception) {
                continue
            }
            for ((name, focus) in jenaFoci) {
                val bound = template.bind(mapOf("this" to focus))
                if (bound.substitution != null) offered++
                val viaText = outcome(jena, bound, null)
                val viaTemplate = outcome(jena, bound, bound.substitution)
                if (viaText != viaTemplate) templateDifferences += "$body with $name\n  text:     $viaText\n  template: $viaTemplate"
                if (knownProviderDifference(body, name)) continue
                val viaRdf4j = outcome(rdf4j, template.bind(mapOf("this" to rdf4jFoci.getValue(name))), null)
                if (!viaText.startsWith("error") && !viaRdf4j.startsWith("error") && viaText != viaRdf4j) {
                    providerDifferences += "$body with $name\n  jena:  $viaText\n  rdf4j: $viaRdf4j"
                }
            }
        }
        assertEquals(emptyList<String>(), templateDifferences, templateDifferences.joinToString("\n"))
        assertEquals(emptyList<String>(), providerDifferences, providerDifferences.joinToString("\n"))
        assertTrue(offered > QUERIES.size, "templates offered: $offered")
    }

    /**
     * Where the providers differ for reasons that are not the template's (the template path agrees with Jena's text
     * on all of them): RDF4J reads `<< s p o >>` as an RDF-star quoted triple, not as an RDF 1.2 reifier, and does not
     * parse `<<( s p o )>>` at all; `STR` of a blank node or a triple term is an error on RDF4J and a string on Jena;
     * and RDF4J matches `?x <urn:p> $this` inside a UNION as if a triple-term `$this` were unbound.
     */
    private fun knownProviderDifference(body: String, focus: String): Boolean =
        "<<" in body ||
            ("STR(" in body && focus in setOf("blank", "reified-blank", "triple-term")) ||
            ("UNION" in body && focus == "triple-term")

    private val shaclPrefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    /**
     * The reviewer's end-to-end case on Jena: triple terms and reifier patterns around `$this` in constraint queries.
     * The expected results are RDF4J's and the text path's (RDF4J reads neither form as RDF 1.2 in this classpath).
     * A result without `?value` has the focus node as its value.
     */
    @Test
    fun `constraints with the focus node in triple terms report the violations of the text`() {
        val data = "ex:r1 ex:says <<( ex:n1 ex:p ex:n2 )>> .\nex:r2 ex:says <<( ex:a ex:p ex:n1 )>> .\n<< ex:n2 ex:p ex:n3 >> ex:src ex:s1 .\n"
        val expected = mapOf(
            "SELECT ${d}this WHERE { FILTER NOT EXISTS { ?r ex:says <<( ${d}this ex:p ?o )>> } }" to listOf("n2 n2", "zzz zzz"),
            "SELECT ${d}this ?value WHERE { ?value ex:says <<( ${d}this ex:p ?o )>> }" to listOf("n1 r1"),
            "SELECT ${d}this ?value WHERE { << ${d}this ex:p ?o >> ex:src ?value }" to listOf("n2 s1"),
            "SELECT ${d}this WHERE { FILTER NOT EXISTS { << ${d}this ex:p ?o >> ex:src ?s } }" to listOf("n1 n1", "zzz zzz"),
            "SELECT ${d}this ?value WHERE { ?value ex:says ?t FILTER(?t = <<( ${d}this ex:p ex:n2 )>>) }" to listOf("n1 r1"),
            "SELECT ${d}this WHERE { FILTER EXISTS { ?r ex:says ?t FILTER(?t = <<( ${d}this ex:p ex:n2 )>>) } }" to listOf("n1 n1"),
            "SELECT ${d}this ?value WHERE { { SELECT ?value WHERE { ?value ex:says <<( ${d}this ex:p ?o )>> } } }" to listOf("n1 r1"),
        )
        val validator = NativeShaclValidator(ValidationConfig.default()) { JenaProvider().createRepository("memory", RdfConfig()) }
        for ((query, violations) in expected) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:n1 , ex:n2 , ex:zzz ; " +
                "sh:sparql [ sh:select \"\"\"PREFIX ex: <http://example.com/ns#> $query\"\"\" ] .\n"
            val report = validator.validate(
                Rdf.parse("$shaclPrefixes\n$data", RdfFormat.TURTLE),
                Rdf.parse("$shaclPrefixes\n$shapes", RdfFormat.TURTLE),
            )
            val got = report.violations.map { r ->
                "${(r.focusNode as Iri).value.substringAfter('#')} ${(r.value as? Iri)?.value?.substringAfter('#') ?: "-"}"
            }.sorted()
            assertEquals(violations, got, query)
        }
    }

    companion object {
        /** [iri] as an [Iri] without the RFC 3987 check of its constructor: data IRIs reach a query unchecked. */
        fun unchecked(iri: String): Iri = Iri::class.java.getMethod("box-impl", String::class.java).invoke(null, iri) as Iri

        val DATA = """
            <urn:n1> <urn:p> <urn:n2> ; <urn:q> 1 .
            <urn:n2> <urn:p> <urn:n3> ; <urn:q> 2 .
            <http://ex/a> <urn:p> <urn:n1> .
            <urn:r1> <urn:says> <<( <urn:n1> <urn:p> <urn:n2> )>> .
            <urn:r2> <urn:says> <<( <http://ex/a> <urn:p> <urn:n1> )>> .
            <urn:r3> <urn:says> <<( <urn:x> <urn:p> <<( <urn:n1> <urn:p> <urn:n2> )>> )>> .
            << <urn:n2> <urn:p> <urn:n3> >> <urn:src> <urn:s1> .
            _:b <urn:kind> "blank" ; <urn:p> <urn:n1> .
            <urn:r4> <urn:says> <<( _:b <urn:p> <urn:n1> )>> .
            _:c <urn:kind> "reified" .
            << _:c <urn:p> <urn:n2> >> <urn:src> <urn:s3> .
            <urn:n1> <urn:lit> "n1" , 1 , "chat"@fr .
        """.trimIndent()

        val QUERIES = listOf(
            "SELECT \$this ?o WHERE { \$this <urn:p> ?o }",
            "SELECT \$this WHERE { ?s <urn:p> \$this }",
            "SELECT \$this ?o WHERE { \$this ?p ?o }",
            "SELECT ?o WHERE { \$this <urn:lit> ?o }",
            "SELECT \$this ?r WHERE { ?r <urn:says> <<( \$this <urn:p> ?o )>> }",
            "SELECT \$this ?r WHERE { ?r <urn:says> <<( ?s <urn:p> \$this )>> }",
            "SELECT \$this ?r WHERE { ?r <urn:says> <<( <urn:x> <urn:p> <<( \$this <urn:p> ?o )>> )>> }",
            "SELECT \$this ?r WHERE { ?r <urn:says> <<( \$this <urn:p> <urn:n3> )>> }",
            "SELECT \$this ?s WHERE { << \$this <urn:p> ?o >> <urn:src> ?s }",
            "SELECT \$this ?s WHERE { << ?x <urn:p> \$this >> <urn:src> ?s }",
            "SELECT \$this WHERE { FILTER NOT EXISTS { ?r <urn:says> <<( \$this <urn:p> ?o )>> } }",
            "SELECT \$this WHERE { FILTER NOT EXISTS { << \$this <urn:p> ?o >> <urn:src> ?s } }",
            "SELECT \$this WHERE { FILTER EXISTS { ?r <urn:says> ?t FILTER EXISTS { ?r <urn:says> <<( \$this <urn:p> ?o )>> } } }",
            "SELECT \$this ?r WHERE { ?r <urn:says> ?t FILTER(?t = <<( \$this <urn:p> <urn:n2> )>>) }",
            "SELECT \$this ?r WHERE { ?r <urn:says> ?t FILTER(sameTerm(?t, <<( \$this <urn:p> <urn:n2> )>>)) }",
            "SELECT \$this ?x WHERE { BIND(<<( \$this <urn:p> <urn:n2> )>> AS ?x) }",
            "SELECT \$this ?x WHERE { BIND(TRIPLE(\$this, <urn:p>, <urn:n2>) AS ?x) }",
            "SELECT \$this ?r WHERE { ?r <urn:says> ?t FILTER(SUBJECT(?t) = \$this) }",
            "SELECT \$this ?r WHERE { { SELECT ?r WHERE { ?r <urn:says> <<( \$this <urn:p> ?o )>> } } }",
            "SELECT \$this ?o WHERE { { SELECT ?o WHERE { \$this <urn:p> ?o } } }",
            "SELECT \$this ?o WHERE { \$this <urn:p>/<urn:p> ?o }",
            "SELECT \$this ?o WHERE { \$this (<urn:p>|^<urn:p>)+ ?o }",
            "SELECT \$this ?o WHERE { VALUES ?o { <urn:n2> <urn:n3> } \$this <urn:p> ?o }",
            "SELECT \$this ?x WHERE { BIND(STR(\$this) AS ?x) }",
            "SELECT \$this ?x WHERE { BIND(IF(isBLANK(\$this), \"b\", IF(isIRI(\$this), \"i\", \"l\")) AS ?x) }",
            "SELECT \$this ?x WHERE { BIND(COALESCE(\$this, 0) AS ?x) }",
            "SELECT \$this WHERE { FILTER(\$this IN (<urn:n1>, <urn:n2>)) }",
            "SELECT \$this ?o WHERE { OPTIONAL { \$this <urn:q> ?o } }",
            "SELECT \$this ?x WHERE { { \$this <urn:q> ?x } UNION { ?x <urn:p> \$this } }",
            "SELECT \$this ?o WHERE { { \$this <urn:p> ?o } MINUS { \$this <urn:q> ?o } }",
            "SELECT (COUNT(?o) AS ?c) WHERE { \$this <urn:p> ?o }",
            "SELECT \$this WHERE { FILTER(BOUND(\$this)) }",
            "SELECT \$this ?x WHERE { BIND(\"\$this\" AS ?x) }",
            "SELECT \$this ?x WHERE { BIND(isTRIPLE(\$this) AS ?x) }",
            "SELECT \$this ?s WHERE { \$this <urn:p> ?o . ?r <urn:says> <<( \$this <urn:p> ?o )>> . BIND(?r AS ?s) }",
        )
    }
}
