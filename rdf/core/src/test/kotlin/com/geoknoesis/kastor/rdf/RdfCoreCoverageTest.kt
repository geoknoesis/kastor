package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdfCoreCoverageTest {

    @Test
    fun `RdfFormat parsing supports aliases and case insensitivity`() {
        assertEquals(RdfFormat.TURTLE, RdfFormat.fromString("turtle"))
        assertEquals(RdfFormat.TURTLE, RdfFormat.fromString("TTL"))
        assertEquals(RdfFormat.JSON_LD, RdfFormat.fromString("jsonld"))
        assertEquals(RdfFormat.N_TRIPLES, RdfFormat.fromString("nt"))
        assertEquals(RdfFormat.RDF_XML, RdfFormat.fromString("rdfxml"))
        assertEquals(RdfFormat.TRIG, RdfFormat.fromString("tri-g"))
        assertEquals(RdfFormat.N_QUADS, RdfFormat.fromString("nq"))
        assertEquals(null, RdfFormat.fromString("unknown"))
    }

    @Test
    fun `RdfFormat quad detection and errors`() {
        assertTrue(RdfFormat.isQuadFormat(RdfFormat.TRIG))
        assertTrue(RdfFormat.isQuadFormat("N-QUADS"))
        assertTrue(!RdfFormat.isQuadFormat(RdfFormat.TURTLE))
        assertThrows(RdfFormatException::class.java) {
            RdfFormat.fromStringOrThrow("not-a-format")
        }
    }

    @Test
    fun `RdfConfig typed helpers map to value classes`() {
        val config = RdfConfig(providerId = "memory", variantId = "default")
        assertEquals(ProviderId("memory"), config.providerIdTyped())
        assertEquals(VariantId("default"), config.variantIdTyped())

        val typed = RdfConfig.of(ProviderId("memory"), VariantId("default"))
        assertEquals("memory", typed.providerId)
        assertEquals("default", typed.variantId)
    }

    @Test
    fun `ProviderCapabilities featureSet maps boolean flags`() {
        val capabilities = ProviderCapabilities(
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsSubSelect = true,
            supportsFederation = true,
            supportsVersionDeclaration = true,
            supportsInference = true,
            supportsServiceDescription = true
        )
        val features = capabilities.featureSet()
        assertTrue(features.contains(SparqlFeature.RDF_STAR))
        assertTrue(features.contains(SparqlFeature.PROPERTY_PATHS))
        assertTrue(features.contains(SparqlFeature.AGGREGATION))
        assertTrue(features.contains(SparqlFeature.SUBSELECT))
        assertTrue(features.contains(SparqlFeature.INFERENCE))
        assertTrue(features.contains(SparqlFeature.FEDERATION))
        assertTrue(features.contains(SparqlFeature.SERVICE_DESCRIPTION))
        assertTrue(features.contains(SparqlFeature.VERSION_DECLARATION))
    }

    @Test
    fun `DefaultRdfProvider is mutable and returns typed id`() {
        val original = DefaultRdfProvider.get()
        try {
            DefaultRdfProvider.set("memory")
            assertEquals("memory", DefaultRdfProvider.get())
            assertEquals(ProviderId("memory"), DefaultRdfProvider.getId())

            DefaultRdfProvider.set(ProviderId("memory"))
            assertEquals("memory", DefaultRdfProvider.get())
        } finally {
            DefaultRdfProvider.set(original)
        }
    }

    @Test
    fun `MapBindingSet typed accessors enforce bindings`() {
        val bindings = MapBindingSet(mapOf(
            "s" to Iri("http://example.org/s"),
            "count" to TypedLiteral("5", XSD.integer)
        ))

        assertEquals("5", bindings.getString("count"))
        assertEquals(5, bindings.getInt("count"))
        assertEquals("missing", bindings.getStringOr("missing", "missing"))

        assertThrows(IllegalArgumentException::class.java) {
            bindings.getStringOrThrow("missing")
        }
        assertThrows(IllegalArgumentException::class.java) {
            bindings.getIntOrThrow("missing")
        }
    }

    @Test
    fun `GraphRef preserves source tracking and delegates`() {
        val repo = Rdf.memory()
        val name = Iri("http://example.org/graph")
        val graph = repo.getGraph(name)
        val ref = graph.asGraphRef(repo, name)

        assertEquals(repo, (ref as SourceTrackedGraph).sourceRepository)
        assertEquals(name, ref.sourceGraphName)
        assertTrue(ref.hasSourceTracking())

        repo.editGraph(name).addTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), string("o")))
        assertEquals(1, ref.getTriples().size)

        repo.close()
    }

    @Test
    fun `UnionGraph deduplicates across sources`() {
        val graph1 = Rdf.graph {
            val s = Iri("http://example.org/s")
            val p = Iri("http://example.org/p")
            s - p - "one"
        }
        val graph2 = Rdf.graph {
            val s = Iri("http://example.org/s")
            val p = Iri("http://example.org/p")
            s - p - "one"
        }
        val union = UnionGraph(listOf(graph1, graph2))
        assertEquals(1, union.getTriples().size)
    }

    @Test
    fun `OptimizedUnionGraph builds FROM clauses and queries repository`() {
        val repository = CapturingQueryRepository()
        val graph1 = Iri("http://example.org/g1")
        val graph2 = Iri("http://example.org/g2")
        val union = OptimizedUnionGraph(repository, listOf(null, graph1, graph2))

        assertTrue(union.hasTriple(RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), string("o"))))

        val triples = union.getTriples()
        assertEquals(1, triples.size)
        assertNotNull(repository.lastAsk)
        assertNotNull(repository.lastSelect)

        val selectText = repository.lastSelect?.sparql ?: error("Expected select to be captured")
        assertTrue(selectText.contains("FROM <http://example.org/g1>"))
        assertTrue(selectText.contains("FROM <http://example.org/g2>"))
    }

    @Test
    fun `OptimizedUnionGraph formats blank nodes in ASK and SELECT`() {
        val repository = CapturingQueryRepository()
        val union = OptimizedUnionGraph(repository, listOf(null))
        val blank = BlankNode("b1")

        union.hasTriple(RdfTriple(blank, Iri("http://example.org/p"), string("o")))
        union.getTriples()

        val askText = repository.lastAsk?.sparql ?: error("Expected ask to be captured")
        val selectText = repository.lastSelect?.sparql ?: error("Expected select to be captured")

        assertTrue(askText.contains("_:b1"))
        assertTrue(askText.contains("ASK") && askText.contains("WHERE"))
        assertTrue(selectText.contains("SELECT ?s ?p ?o") && selectText.contains("WHERE"))
    }

    private class CapturingQueryRepository(
        private val delegate: RdfRepository = Rdf.memory()
    ) : RdfRepository by delegate {
        var lastSelect: SparqlSelect? = null
        var lastAsk: SparqlAsk? = null

        override fun select(query: SparqlSelect): SparqlQueryResult {
            lastSelect = query
            val binding = MapBindingSet(
                mapOf(
                    "s" to Iri("http://example.org/s"),
                    "p" to Iri("http://example.org/p"),
                    "o" to string("o"),
                    "count" to TypedLiteral("1", XSD.integer)
                )
            )
            return sparqlQueryResult(listOf(binding))
        }

        override fun ask(query: SparqlAsk): Boolean {
            lastAsk = query
            return true
        }

        override fun construct(query: SparqlConstruct): Sequence<RdfTriple> = emptySequence()

        override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = emptySequence()

        override fun update(query: UpdateQuery) = Unit
    }
}

