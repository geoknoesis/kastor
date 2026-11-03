package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.BooleanPredicate
import org.geoknoesis.lens.CompareOperator
import org.geoknoesis.lens.Filters
import org.geoknoesis.lens.Lens
import org.geoknoesis.lens.LensDsl
import org.geoknoesis.lens.LensOptimizer
import org.geoknoesis.lens.SparqlCompiler
import org.geoknoesis.lens.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for semantic equivalence on both SPARQL providers. */
class LensOptimizerProviderTest {
    private val company = "urn:optimizer:Company"
    private val age = "urn:optimizer:age"
    private val active = "urn:optimizer:active"
    private val typeIri = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val integer = Iri("http://www.w3.org/2001/XMLSchema#integer")
    private val boolean = Iri("http://www.w3.org/2001/XMLSchema#boolean")
    private val a = Iri("urn:optimizer:a")
    private val b = Iri("urn:optimizer:b")
    private val c = Iri("urn:optimizer:c")
    private val d = Iri("urn:optimizer:d")

    private fun fixture(repo: RdfRepository) {
        val graph = repo.editDefaultGraph()
        listOf(a, b, c, d).forEach {
            graph.addTriple(RdfTriple(it, typeIri, Iri(company)))
        }
        graph.addTriple(RdfTriple(a, Iri(age), lit("22", integer)))
        graph.addTriple(RdfTriple(b, Iri(age), lit("16", integer)))
        graph.addTriple(RdfTriple(d, Iri(age), lit("30", integer)))
        for (node in listOf(a, c, d)) {
            graph.addTriple(RdfTriple(node, Iri(active), lit("true", boolean)))
        }
    }

    private fun verify(factory: () -> RdfRepository) {
        factory().use { repo ->
            fixture(repo)
            val root = LensDsl.type(company)
            val adult = Filters.compare(
                Filters.path(age), CompareOperator.GT,
                Filters.constant(Value.TypedLiteral("18", integer.value))
            )
            val enabled = Filters.compare(
                Filters.path(active), CompareOperator.EQ,
                Filters.constant(Value.TypedLiteral("true", boolean.value))
            )
            val nested = LensDsl.filter(
                LensDsl.filter(root, Filters.not(Filters.not(adult))),
                Filters.all(enabled, Filters.all(enabled))
            )
            val compiler = SparqlCompiler()
            val explained = compiler.explain(nested)
            assertTrue(explained.plan.nodesAfter < explained.plan.nodesBefore)
            assertTrue("eliminate-double-negation" in explained.plan.rules)
            assertTrue("fuse-consecutive-node-filters" in explained.plan.rules)
            assertTrue("flatten-and-deduplicate-boolean-conditions" in explained.plan.rules)
            val optimized = explained.plan.lens as Lens.Filter
            assertTrue(optimized.input is Lens.Type)
            assertTrue(optimized.condition is BooleanPredicate.All)
            assertEquals(explained.sparql, compiler.select(nested))
            val executor = KastorLensProviderBridge(repo)
            assertEquals(setOf(a, d), executor.nodes(explained.sparql))
            assertEquals(setOf(a, d), executor.nodes(compiler.select(LensDsl.and(nested, nested))))

            // Missing age is part of NOT(age > 18); this is dataset-relative,
            // not OWL open-world negation.
            val notAdult = LensDsl.filter(root, Filters.not(adult))
            assertEquals(setOf(b, c), executor.nodes(compiler.select(notAdult)))
            assertEquals(
                setOf(b, c), executor.nodes(compiler.select(LensDsl.minus(root, LensDsl.filter(root, adult))))
            )
            assertEquals(
                setOf(a, b, c, d),
                executor.nodes(compiler.select(LensDsl.or(nested, notAdult)))
            )
        }
    }

    @Test fun jenaOptimizedAlgebra() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jOptimizedAlgebra() = verify { Rdf4jRepository.MemoryRepository() }

    @Test fun rejectEmptyBooleanGroupsBeforeSerialization() {
        val root = LensDsl.type(company)
        assertThrows(IllegalArgumentException::class.java) {
            SparqlCompiler().select(LensDsl.filter(root, BooleanPredicate.All(emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SparqlCompiler().select(LensDsl.filter(root, BooleanPredicate.Any(emptyList())))
        }
    }
}
