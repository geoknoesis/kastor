package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Reload detection uses a collision-resistant digest of the graph content, and a failed load keeps the validator's
 * previously loaded data and shapes together.
 */
class Rdf4jReloadTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private fun components(result: ValidationResult): List<String> =
        (result as? ValidationResult.Violations)?.items?.map { it.constraintIri.value.substringAfter('#') }?.sorted() ?: emptyList()

    @Test
    fun `a change that keeps every triple hash code still forces a reload`() {
        val shapes = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:pattern "^A" ] .
        """.trimIndent()
        val p = ex("p")
        val aa = RdfTriple(p, ex("name"), Literal("Aa"))
        val bb = RdfTriple(p, ex("name"), Literal("BB"))
        assertEquals(aa.hashCode(), bb.hashCode(), "precondition: \"Aa\" and \"BB\" have the same String hash code")

        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g = MemoryGraph()
            g.addTriple(RdfTriple(p, RDF.type, ex("Person")))
            g.addTriple(aa)
            assertEquals(ValidationResult.Ok, v.validate(g, p))
            g.removeTriple(aa)
            g.addTriple(bb)
            assertEquals(listOf("PatternConstraintComponent"), components(v.validate(g, p)))
        }
    }

    @Test
    fun `a failed load leaves the previously loaded data and shapes consistent`() {
        // Shapes embedded in the data: graph A requires ex:name, graph B requires ex:code.
        val a = Rdf.parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:NameShape a sh:NodeShape ; sh:targetClass ex:Person ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            ex:p a ex:Person ; ex:code "c" .
            """.trimIndent(),
        )
        val b = Rdf.parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:CodeShape a sh:NodeShape ; sh:targetClass ex:Person ; sh:property [ sh:path ex:code ; sh:minCount 1 ] .
            ex:p a ex:Person ; ex:name "n" .
            """.trimIndent(),
        )
        Rdf4jValidation().use { v ->
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(a, ex("p"))))

            v.beforeLoadCommit = { throw IllegalStateException("simulated store failure") }
            assertThrows(IllegalStateException::class.java) { v.validate(b, ex("p")) }
            v.beforeLoadCommit = null

            // A is still the loaded graph, with A's shapes: ex:p lacks ex:name.
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(a, ex("p"))))
            val items = (v.validate(a, ex("p")) as ValidationResult.Violations).items
            assertEquals(listOf(ex("name")), items.map { it.path })
            // B is loaded properly once the store works again: ex:p has a name but lacks ex:code.
            assertEquals(listOf(ex("code")), (v.validate(b, ex("p")) as ValidationResult.Violations).items.map { it.path })
        }
    }
}
