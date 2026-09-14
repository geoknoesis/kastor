package com.geoknoesis.kastor.examples.dcat

import com.geoknoesis.kastor.examples.dcat.generated.Catalog
import com.geoknoesis.kastor.examples.dcat.generated.Dataset
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/** Reads the interfaces generated from the DCAT-US 3.0 SHACL shapes during this build. */
class DCAT_US_ExampleTest {

    private val dct = "http://purl.org/dc/terms/"
    private val dcat = "http://www.w3.org/ns/dcat#"

    @Test
    fun `generated DCAT-US Catalog materializes from RDF`() {
        val graph = MemoryGraph()
        val node = Iri("https://data.example.gov/catalog")
        graph.addTriple(RdfTriple(node, RDF.type, Iri("${dcat}Catalog")))

        val catalog: Catalog = graph.materialize(node)

        assertTrue(catalog is RdfBacked)
        assertEquals(node, (catalog as RdfBacked).rdf.node)
    }

    @Test
    fun `generated DCAT-US types read values of different datatypes and nested objects`() {
        val graph = MemoryGraph()
        val catalog = Iri("https://data.example.gov/catalog")
        val dataset = Iri("https://data.example.gov/dataset/budget")
        val distribution = Iri("https://data.example.gov/dataset/budget/csv")
        fun add(subject: Iri, predicate: String, value: com.geoknoesis.kastor.rdf.RdfTerm) =
            graph.addTriple(RdfTriple(subject, Iri(predicate), value))

        add(catalog, RDF.type.value, Iri("${dcat}Catalog"))
        add(catalog, "${dct}title", Literal("Example Government Data Catalog"))
        add(catalog, "${dct}title", LangString("Beispielkatalog", "de"))
        add(catalog, "${dct}issued", TypedLiteral("2024-05-01", XSD.date))
        add(catalog, "${dcat}keyword", Literal("open data"))
        add(catalog, "${dcat}keyword", Literal("budget"))

        add(dataset, RDF.type.value, Iri("${dcat}Dataset"))
        add(dataset, "${dcat}spatialResolutionInMeters", TypedLiteral("30.5", XSD.decimal))
        add(dataset, "${dcat}distribution", distribution)

        add(distribution, RDF.type.value, Iri("${dcat}Distribution"))
        add(distribution, "${dcat}byteSize", TypedLiteral("12345678901234567890", Iri("http://www.w3.org/2001/XMLSchema#nonNegativeInteger")))
        add(distribution, "${dcat}temporalResolution", TypedLiteral("PT15M", Iri("http://www.w3.org/2001/XMLSchema#duration")))

        val typedCatalog: Catalog = graph.materialize(catalog)
        assertEquals(setOf("Example Government Data Catalog", "Beispielkatalog"), typedCatalog.title.toSet())
        assertEquals(setOf("open data", "budget"), typedCatalog.keyword.toSet())
        assertEquals("2024-05-01", typedCatalog.issued)

        val typedDataset: Dataset = graph.materialize(dataset)
        assertEquals(BigDecimal("30.5"), typedDataset.spatialResolutionInMeters)
        val typedDistribution = typedDataset.distribution.single()
        assertEquals(distribution, (typedDistribution as RdfBacked).rdf.node)
        assertEquals(BigInteger("12345678901234567890"), typedDistribution.byteSize)
        assertEquals("PT15M", typedDistribution.temporalResolution)
    }
}
