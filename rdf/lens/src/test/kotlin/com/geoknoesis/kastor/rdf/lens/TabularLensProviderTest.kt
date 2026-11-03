package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.LensDsl
import org.geoknoesis.lens.TabularDsl
import org.geoknoesis.lens.TabularLens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Provider-side projection and aggregation, tested with real Kastor repositories. */
class TabularLensProviderTest {
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val company = Iri("urn:tabular:Company")
    private val sector = Iri("urn:tabular:sector")
    private val region = Iri("urn:tabular:region")
    private val healthcare = Iri("urn:tabular:Healthcare")
    private val industrial = Iri("urn:tabular:Industrial")
    private val us = Iri("urn:tabular:US")
    private val eu = Iri("urn:tabular:EU")
    private val a = Iri("urn:tabular:a")
    private val b = Iri("urn:tabular:b")
    private val c = Iri("urn:tabular:c")
    private val d = Iri("urn:tabular:d")

    private fun fixture(repo: RdfRepository) {
        val graph = repo.editDefaultGraph()
        listOf(a, b, c, d).forEach { graph.addTriple(RdfTriple(it, type, company)) }
        listOf(a, b).forEach { graph.addTriple(RdfTriple(it, sector, healthcare)) }
        listOf(a, c).forEach { graph.addTriple(RdfTriple(it, sector, industrial)) }
        listOf(a, b, d).forEach { graph.addTriple(RdfTriple(it, region, us)) }
        graph.addTriple(RdfTriple(c, region, eu))
    }

    private fun count(row: Map<String, RdfTerm?>): Int =
        ((row["count"] as? Literal)?.lexical ?: error("Expected numeric count")).toInt()

    private fun verify(factory: () -> RdfRepository) {
        factory().use { repo ->
            fixture(repo)
            val source = LensDsl.type(company.value)
            val executor = KastorTabularLensExecutor(repo)

            val all = executor.execute(TabularDsl.count(source))
            assertEquals(listOf("count"), all.columns)
            assertEquals(4, count(all.rows.single()))

            // Empty selections must return one zero-count row, but no facet buckets.
            val emptySource = LensDsl.type("urn:tabular:NoMatchingClass")
            val emptyCount = executor.execute(TabularDsl.count(emptySource))
            assertEquals(0, count(emptyCount.rows.single()))
            assertTrue(executor.execute(TabularDsl.facet(emptySource, sector.value, includeMissing = true)).rows.isEmpty())

            val projected = executor.execute(TabularDsl.project(
                source,
                TabularLens.Column("sector", sector.value),
                TabularLens.Column("region", region.value, required = true)
            ))
            assertEquals(listOf("node", "sector", "region"), projected.columns)
            assertEquals(5, projected.rows.size) // multi-valued sector yields two rows for a
            assertEquals(2, projected.rows.count { it["node"] == a })
            assertEquals(null, projected.rows.single { it["node"] == d }["sector"])
            assertTrue(projected.rows.all { it["region"] != null })

            val facets = executor.execute(TabularDsl.facet(source, sector.value, includeMissing = true))
            assertEquals(listOf("facetValue", "count"), facets.columns)
            assertEquals(mapOf(
                healthcare to 2, industrial to 2, null to 1
            ), facets.rows.associate { it["facetValue"] to count(it) })

            val onlyValues = executor.execute(TabularDsl.facet(source, sector.value))
            assertEquals(2, onlyValues.rows.size)
            assertTrue(onlyValues.rows.none { it["facetValue"] == null })

            val grouped = executor.execute(TabularDsl.groupCount(
                source,
                TabularLens.Column("byRegion", region.value, required = true),
                TabularLens.Column("bySector", sector.value)
            ))
            val keyed = grouped.rows.associate {
                (it["byRegion"] to it["bySector"]) to count(it)
            }
            assertEquals(4, keyed.size)
            assertEquals(2, keyed[us to healthcare])
            assertEquals(1, keyed[us to industrial])
            assertEquals(1, keyed[eu to industrial])
            assertEquals(1, keyed[us to null])

            assertThrows(IllegalStateException::class.java) {
                executor.execute(
                    TabularDsl.facet(source, sector.value, includeMissing = true),
                    KastorTabularLensExecutor.Limits(maxRows = 2)
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                executor.explain(TabularDsl.facet(source, "urn:bad> UNION { ?s ?p ?o }"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                TabularDsl.project(source,
                    TabularLens.Column("duplicate", sector.value),
                    TabularLens.Column("duplicate", region.value))
            }
        }
    }

    @Test fun jenaTabularLens() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jTabularLens() = verify { Rdf4jRepository.MemoryRepository() }
}
