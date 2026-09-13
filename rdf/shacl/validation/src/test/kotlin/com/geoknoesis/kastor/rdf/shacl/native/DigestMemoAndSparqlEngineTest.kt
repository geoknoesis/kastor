package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfErrorCode
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfProviderException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigestMemoAndSparqlEngineTest {
    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun graph(ttl: String) = Rdf.parse(prefixes + ttl, RdfFormat.TURTLE)

    @Test fun `digest is reused for an unchanged shapes graph and recomputed after mutation`() {
        val data = graph("ex:a ex:p 1 .")
        val shape = Iri("http://example.org/S")
        val property = Iri("http://example.org/ps")
        val shapes: MutableRdfGraph = Rdf.graph {
            shape - com.geoknoesis.kastor.rdf.vocab.RDF.type - SHACL.NodeShape
            shape - SHACL.targetNode - Iri("http://example.org/a")
            shape - SHACL.`property` - property
            property - SHACL.path - Iri("http://example.org/p")
            property - SHACL.minCount - 1
        }
        val validator = NativeShaclValidator(ValidationConfig.default())
        assertTrue(validator.validate(data, shapes).isValid)
        assertTrue(validator.validate(data, shapes).isValid)
        assertEquals(1L, validator.digestMemoHits)

        // Same size, different content: must not reuse the stale digest / compiled shapes.
        val old = shapes.getTriples().single { it.predicate == SHACL.minCount }
        assertTrue(shapes.removeTriple(old))
        shapes.addTriple(RdfTriple(property, SHACL.minCount, TypedLiteral("2", XSD.integer)))
        val mutated = validator.validate(data, shapes)
        assertFalse(mutated.isValid)
        assertEquals(1L, validator.digestMemoHits)
    }

    @Test fun `missing SPARQL provider yields a clear exception and consistent capabilities`() {
        val noEngine: () -> com.geoknoesis.kastor.rdf.RdfRepository = {
            throw RdfProviderException("no SPARQL-capable provider", RdfErrorCode.PROVIDER_NOT_FOUND)
        }
        val provider = NativeShaclValidatorProvider(sparqlEngineAvailable = { false }, sparqlRepositoryFactory = noEngine)
        assertFalse(provider.getCapabilities().supportsShaclSparql)
        assertFalse(ValidationProfile.SHACL_SPARQL in provider.getSupportedProfiles())
        assertFalse(provider.isSupported(ValidationProfile.SHACL_SPARQL))

        val validator = provider.createValidator(ValidationConfig.default())
        val data = graph("ex:a ex:p 1 .")
        // Core-only shapes never touch the engine.
        assertTrue(validator.validate(data, graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeKind sh:IRI .")).isValid)

        val sparqlShapes = graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select \"SELECT \$this WHERE { \$this ?p ?o }\" ] .")
        val error = assertFailsWith<ShaclValidationException> { validator.validate(data, sparqlShapes) }
        assertTrue(error.message!!.contains("rdf-jena") && error.message!!.contains("rdf-rdf4j"), error.message)
        assertTrue(error.cause is RdfProviderException)

        // With a provider on the classpath the default provider advertises SHACL-SPARQL.
        assertTrue(NativeShaclValidatorProvider().getCapabilities().supportsShaclSparql)
        assertTrue(ValidationProfile.SHACL_SPARQL in NativeShaclValidatorProvider().getSupportedProfiles())
    }
}
