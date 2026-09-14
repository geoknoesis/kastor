package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The validator evaluates only the shapes that target the focus node, reuses the data loaded for an unchanged graph,
 * and still reloads when the graph changed (even without a size change).
 */
class Rdf4jFocusValidationTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
            sh:property [ sh:path ex:knows ; sh:node ex:NamedShape ] .
        ex:NamedShape a sh:NodeShape ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
        ex:SpecialShape a sh:NodeShape ; sh:targetNode ex:special ; sh:property [ sh:path ex:code ; sh:minCount 1 ] .
        ex:ManagerShape a sh:NodeShape ; sh:targetSubjectsOf ex:manages ; sh:property [ sh:path ex:title ; sh:minCount 1 ] .
        ex:DepartmentShape a sh:NodeShape ; sh:targetObjectsOf ex:managedBy ; sh:property [ sh:path ex:budget ; sh:minCount 1 ] .
    """.trimIndent()

    private val data = """
        @prefix ex: <http://example.org/> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        ex:Student rdfs:subClassOf ex:Person .
        ex:student a ex:Student .
        ex:pat a ex:Person ; ex:name "Pat" ; ex:knows ex:nameless .
        ex:special ex:other "x" .
        ex:boss ex:manages ex:thing .
        ex:worker ex:managedBy ex:dept .
        ex:clean a ex:Person ; ex:name "Clean" ; ex:knows ex:pat .
    """.trimIndent()

    private fun components(result: ValidationResult): List<String> =
        (result as? ValidationResult.Violations)?.items?.map { it.constraintIri.value.substringAfter('#') }?.sorted() ?: emptyList()

    @Test
    fun `every target kind is honoured for the focus node only`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g = Rdf.parse(data)
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(g, ex("student"))))
            assertEquals(listOf("NodeConstraintComponent"), components(v.validate(g, ex("pat"))))
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(g, ex("special"))))
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(g, ex("boss"))))
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(g, ex("dept"))))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("clean")))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("nameless")), "a node only reached through sh:node is not a target")
            val items = (v.validate(g, ex("pat")) as ValidationResult.Violations).items
            assertTrue(items.all { it.focusNode == ex("pat") })
        }
    }

    @Test
    fun `changes to the same graph are seen even when its size does not change`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g = MemoryGraph()
            val p = ex("p")
            g.addTriple(RdfTriple(p, RDF.type, ex("Person")))
            val name = RdfTriple(p, ex("name"), Literal("P"))
            g.addTriple(name)
            assertEquals(ValidationResult.Ok, v.validate(g, p))
            g.removeTriple(name)
            g.addTriple(RdfTriple(p, ex("nickname"), Literal("P")))
            assertEquals(listOf("MinCountConstraintComponent"), components(v.validate(g, p)))
        }
    }

    @Test
    fun `validating each resource of a large graph stays fast and correct`() {
        val g = MemoryGraph()
        val people = (0 until 1000).map { ex("person$it") }
        people.forEachIndexed { i, p ->
            g.addTriple(RdfTriple(p, RDF.type, ex("Person")))
            if (i % 10 != 0) g.addTriple(RdfTriple(p, ex("name"), Literal("n$i")))
            g.addTriple(RdfTriple(p, ex("age"), Literal("$i")))
        }
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val started = System.nanoTime()
            val invalid = people.count { v.validate(g, it) is ValidationResult.Violations }
            val seconds = (System.nanoTime() - started) / 1e9
            assertEquals(100, invalid)
            assertTrue(seconds < 60, "1000 focus validations took ${"%.1f".format(seconds)}s")
        }
    }
}
