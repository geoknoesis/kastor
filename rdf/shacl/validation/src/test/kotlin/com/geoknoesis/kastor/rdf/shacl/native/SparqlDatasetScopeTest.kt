package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SHACL-SPARQL constraints see exactly the data graph (the dataset's default graph, never the union of its named
 * graphs), and blank-node focus nodes are pre-bound by the engine itself on every provider, including inside
 * sub-selects.
 */
class SparqlDatasetScopeTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")
    private val d = '$'

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validator(provider: String) = NativeShaclValidator(ValidationConfig.default()) { repository(provider) }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `validateDataset SPARQL constraints do not see named graph data`(provider: String) {
        repository(provider).use { repo ->
            repo.transaction {
                editDefaultGraph().addTriples(g("ex:a ex:p 1 .").getTriples())
                editGraph(ex("named")).addTriples(g("ex:a ex:p 2 .").getTriples())
            }
            val shapes = g(
                """
                ex:S a sh:NodeShape ; sh:targetNode ex:a ;
                  sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { ${d}this <http://example.org/p> ?value . FILTER (?value > 1) }''' ] .
                """,
            )
            val report = validator(provider).validateDataset(repo, shapes)
            assertTrue(report.isValid, report.violations.toString())
        }
    }

    private val subSelectShapes = """
        ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ;
          sh:sparql [ sh:select '''
            SELECT ${d}this ?value WHERE {
              { SELECT ${d}this ?value WHERE { ${d}this <http://example.org/p> ?value . FILTER (isBlank(${d}this) && !isIRI(${d}this) && ?value > 1) } }
            }''' ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `blank node focus nodes are pre-bound inside sub-selects on the copy path`(provider: String) {
        val data = g("[] ex:p 1 . [] ex:p 2 .")
        val v = validator(provider).validate(data, g(subSelectShapes)).violations.single()
        assertTrue(v.focusNode is BlankNode)
        assertEquals(data.getTriples().single { (it.obj as Literal).lexical == "2" }.subject, v.focusNode)
        assertEquals("2", (v.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `blank node focus nodes are pre-bound inside sub-selects for in-place datasets`(provider: String) {
        repository(provider).use { repo ->
            repo.transaction { editDefaultGraph().addTriples(g("[] ex:p 1 . [] ex:p 2 .").getTriples()) }
            val report = validator(provider).validateDataset(repo, g(subSelectShapes))
            val v = report.violations.single()
            assertTrue(v.focusNode is BlankNode)
            assertEquals("2", (v.value as Literal).lexical)
        }
    }

    /** `$this` occurs only in a FILTER of a `SELECT *` sub-query: nothing inside the sub-query can bind it. */
    private val filterOnlySubSelect = """
        ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ;
          sh:sparql [ sh:select '''SELECT ${d}this WHERE { { SELECT * WHERE { FILTER (isBlank(${d}this)) } } }''' ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `blank node focus nodes reach a filter-only SELECT star sub-query`(provider: String) {
        val data = g("[] ex:p 1 . [] ex:p 2 .")
        val copy = validator(provider).validate(data, g(filterOnlySubSelect))
        assertEquals(2, copy.violations.size, copy.violations.toString())
        assertTrue(copy.violations.all { it.focusNode is BlankNode })
        repository(provider).use { repo ->
            repo.transaction { editDefaultGraph().addTriples(data.getTriples()) }
            val inPlace = validator(provider).validateDataset(repo, g(filterOnlySubSelect))
            assertEquals(2, inPlace.violations.size, inPlace.violations.toString())
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `blank node result values are the data graph's blank nodes`(provider: String) {
        val data = g("ex:a ex:p [ ex:q 1 ] .")
        val shapes = g(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { ${d}this <http://example.org/p> ?value . FILTER (isBlank(?value) && !isIRI(?value)) }''' ] .
            """,
        )
        val v = validator(provider).validate(data, shapes).violations.single()
        assertEquals(data.getTriples().single { it.predicate == ex("p") }.obj, v.value)
    }
}
