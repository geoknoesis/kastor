package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.LensDsl
import org.geoknoesis.lens.SparqlCompiler
import org.geoknoesis.lens.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Compile the typed lens algebra, then execute its generated SPARQL on real providers. */
class CompiledLensProviderTest {
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val company = "urn:lens:Company"
    private val restricted = "urn:lens:RestrictedSupplier"
    private val active = "urn:lens:ActiveExemption"
    private val sector = "urn:lens:sector"
    private val healthcare = "urn:lens:Healthcare"
    private val supplierProperty = "urn:lens:suppliesTo"
    private val exemptionProperty = "urn:lens:hasExemption"
    private val a = Iri("urn:lens:a")
    private val b = Iri("urn:lens:b")
    private val c = Iri("urn:lens:c")

    private fun verify(factory: () -> RdfRepository) {
        factory().use { repo ->
            val graph = repo.editDefaultGraph()
            listOf(a,b,c).forEach { graph.addTriple(RdfTriple(it, rdfType, Iri(company))) }
            listOf(a,b).forEach { graph.addTriple(RdfTriple(it, Iri(sector), Iri(healthcare))) }
            val supplier = Iri("urn:lens:supplier")
            val waiver = Iri("urn:lens:waiver")
            graph.addTriple(RdfTriple(supplier, rdfType, Iri(restricted)))
            graph.addTriple(RdfTriple(waiver, rdfType, Iri(active)))
            listOf(a,b).forEach {
                graph.addTriple(RdfTriple(it, Iri(supplierProperty), supplier))
            }
            graph.addTriple(RdfTriple(b, Iri(exemptionProperty), waiver))

            val allCompanies = LensDsl.type(company)
            val healthcareCompanies = LensDsl.eq(
                allCompanies, sector, Value.Iri(healthcare)
            )
            val withRisk = LensDsl.exists(
                allCompanies, supplierProperty, LensDsl.type(restricted)
            )
            val exempt = LensDsl.exists(
                allCompanies, exemptionProperty, LensDsl.type(active)
            )
            val eligible = LensDsl.and(healthcareCompanies, withRisk)
            val highRisk = LensDsl.minus(eligible, exempt)
            val compiler = SparqlCompiler()
            val executor = KastorLensProviderBridge(repo)

            assertEquals(setOf(a), executor.nodes(compiler.select(highRisk)))
            assertEquals(setOf(a,b), executor.nodes(compiler.select(eligible)))
            assertEquals(setOf(a,b,c), executor.nodes(compiler.select(
                LensDsl.or(eligible, allCompanies)
            )))
            assertEquals(setOf(a), executor.nodes(compiler.select(
                LensDsl.and(highRisk, highRisk)
            )))
            assertThrows(IllegalArgumentException::class.java) {
                compiler.select(LensDsl.type("urn:unsafe> } UNION { ?s ?p ?o"))
            }
        }
    }
    @Test fun jenaCompiledLens() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jCompiledLens() = verify { Rdf4jRepository.MemoryRepository() }
}
