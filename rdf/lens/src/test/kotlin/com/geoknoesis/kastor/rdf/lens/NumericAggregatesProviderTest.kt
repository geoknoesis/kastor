package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.LensDsl
import org.geoknoesis.lens.NumericOperation
import org.geoknoesis.lens.TabularDsl
import org.geoknoesis.lens.TabularLens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Numeric aggregate semantics are exercised on the real Jena and RDF4J backends. */
class NumericAggregatesProviderTest {
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val integer = Iri("http://www.w3.org/2001/XMLSchema#integer")
    private val company = Iri("urn:agg:Company")
    private val region = Iri("urn:agg:region")
    private val amount = Iri("urn:agg:amount")
    private val us = Iri("urn:agg:US")
    private val eu = Iri("urn:agg:EU")
    private val apac = Iri("urn:agg:APAC")
    private val a = Iri("urn:agg:a")
    private val b = Iri("urn:agg:b")
    private val c = Iri("urn:agg:c")
    private val d = Iri("urn:agg:d")
    private val e = Iri("urn:agg:e")
    private val f = Iri("urn:agg:f")
    private val g = Iri("urn:agg:g")

    private fun fixture(repo: RdfRepository) {
        val graph = repo.editDefaultGraph()
        listOf(a, b, c, d, e, f, g).forEach {
            graph.addTriple(RdfTriple(it, type, company))
        }
        listOf(a, b, d, e).forEach {
            graph.addTriple(RdfTriple(it, region, us))
        }
        listOf(a, c).forEach {
            graph.addTriple(RdfTriple(it, region, eu))
        }
        graph.addTriple(RdfTriple(g, region, apac))

        // a has multiple numeric values, and a/b both have "10": both must count.
        graph.addTriple(RdfTriple(a, amount, lit("10", integer)))
        graph.addTriple(RdfTriple(a, amount, lit("20", integer)))
        graph.addTriple(RdfTriple(b, amount, lit("10", integer)))
        graph.addTriple(RdfTriple(c, amount, lit("5", integer)))
        graph.addTriple(RdfTriple(f, amount, lit("7", integer))) // Missing region
        graph.addTriple(RdfTriple(e, amount, lit("invalid")))  // Not numeric
        // d and g have no numeric amount.
    }

    private fun numeric(term: RdfTerm?): BigDecimal {
        val value = term as? Literal ?: error("Numeric aggregate result must be an RDF literal")
        return BigDecimal(value.lexical)
    }

    private fun assertNumeric(expected: String, term: RdfTerm?) {
        assertEquals(0, numeric(term).compareTo(BigDecimal(expected)))
    }

    private fun assertApprox(expected: String, term: RdfTerm?) {
        val difference = numeric(term).subtract(BigDecimal(expected)).abs()
        assertTrue(difference < BigDecimal("0.0000001"), "Numeric difference $difference")
    }

    private fun verify(factory: () -> RdfRepository) {
        factory().use { repo ->
            fixture(repo)
            val source = LensDsl.type(company.value)
            val executor = KastorTabularLensExecutor(repo)

            val summedGlobal = executor.execute(TabularDsl.sum(source, amount.value))
            assertEquals(listOf("sum"), summedGlobal.columns)
            assertNumeric("52", summedGlobal.rows.single()["sum"])
            val averagedGlobal = executor.execute(TabularDsl.avg(source, amount.value))
            assertEquals(listOf("avg"), averagedGlobal.columns)
            assertNumeric("10.4", averagedGlobal.rows.single()["avg"])
            val minimumGlobal = executor.execute(TabularDsl.min(source, amount.value))
            assertEquals(listOf("min"), minimumGlobal.columns)
            assertNumeric("5", minimumGlobal.rows.single()["min"])
            val maximumGlobal = executor.execute(TabularDsl.max(source, amount.value))
            assertEquals(listOf("max"), maximumGlobal.columns)
            assertNumeric("20", maximumGlobal.rows.single()["max"])

            val sparql = executor.explain(TabularDsl.sum(source, amount.value)).sparql
            assertTrue(sparql.contains("SELECT DISTINCT ?node"))
            assertTrue(sparql.contains("FILTER(isNumeric(?__lensNumericValue))"))
            assertTrue(sparql.contains("SELECT DISTINCT ?node ?__lensNumericValue"))
            assertTrue(sparql.contains("COUNT(?__lensNumericValue) AS ?__lensNumericCount"))

            val dimension = TabularLens.Column("byRegion", region.value)
            val summed = executor.execute(TabularDsl.sum(source, amount.value, dimension))
            assertEquals(listOf("byRegion", "sum"), summed.columns)
            val sums = summed.rows.associate { it["byRegion"] to it["sum"] }
            assertEquals(4, sums.size)
            assertNumeric("40", sums[us])
            assertNumeric("35", sums[eu])
            assertNumeric("7", sums[null])
            assertNumeric("0", sums[apac])

            val averages = executor.execute(TabularDsl.avg(source, amount.value, dimension))
            val avgByRegion = averages.rows.associate { it["byRegion"] to it["avg"] }
            assertApprox("13.33333333333333333333333333333333", avgByRegion[us]) // 40/3
            assertApprox("11.66666666666666666666666666666667", avgByRegion[eu]) // 35/3
            assertNull(avgByRegion[apac])

            val minimum = executor.execute(TabularDsl.min(source, amount.value, dimension))
            val minimumByRegion = minimum.rows.associate { it["byRegion"] to it["min"] }
            assertNumeric("10", minimumByRegion[us])
            assertNumeric("5", minimumByRegion[eu])
            assertNull(minimumByRegion[apac])

            val maximum = executor.execute(TabularDsl.max(source, amount.value, dimension))
            val maximumByRegion = maximum.rows.associate { it["byRegion"] to it["max"] }
            assertNumeric("20", maximumByRegion[us])
            assertNumeric("20", maximumByRegion[eu])
            assertNull(maximumByRegion[apac])

            val empty = LensDsl.type("urn:agg:MissingClass")
            assertNumeric("0", executor.execute(TabularDsl.sum(empty, amount.value)).rows.single()["sum"])
            for (operation in listOf(NumericOperation.AVG, NumericOperation.MIN, NumericOperation.MAX)) {
                val result = executor.execute(TabularDsl.aggregate(empty, amount.value, operation, "result"))
                assertNull(result.rows.single()["result"])
            }

            assertThrows(IllegalStateException::class.java) {
                executor.execute(TabularDsl.sum(source, amount.value, dimension),
                    KastorTabularLensExecutor.Limits(maxRows = 2))
            }
            assertThrows(IllegalArgumentException::class.java) {
                executor.explain(TabularDsl.sum(source, "urn:malicious> } UNION { ?s ?p ?o"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                TabularDsl.aggregate(source, amount.value, NumericOperation.SUM, "x) UNION {")
            }
            assertThrows(IllegalArgumentException::class.java) {
                TabularDsl.aggregate(source, amount.value, NumericOperation.SUM, "byRegion", dimension)
            }
            assertThrows(IllegalArgumentException::class.java) {
                TabularDsl.sum(source, amount.value, *Array(4) {
                    TabularLens.Column("r$it", region.value)
                })
            }
        }
    }

    @Test fun jenaNumericAggregates() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jNumericAggregates() = verify { Rdf4jRepository.MemoryRepository() }
}