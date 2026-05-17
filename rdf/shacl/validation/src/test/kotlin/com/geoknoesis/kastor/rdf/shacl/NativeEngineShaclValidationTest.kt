package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.providers.MemoryShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeEngineShaclValidationTest {

    @Test
    fun `AUTO resolves kastor native before memory`() {
        val validator = ValidatorRegistry.createValidator(ValidationConfig(profile = ValidationProfile.SHACL_CORE))
        val dataGraph = Rdf.graph {
            val p = Iri("http://example.org/p1")
            p - RDF.type - Iri("http://example.org/Person")
            p - Iri("http://example.org/label") - "ok"
        }
        val shapesGraph = Rdf.graph {
            val shape = Iri("http://example.org/PersonShape")
            val ps = Iri("http://example.org/ps1")
            shape - RDF.type - SHACL.NodeShape
            shape - SHACL.targetClass - Iri("http://example.org/Person")
            shape - SHACL.`property` - ps
            ps - RDF.type - SHACL.PropertyShape
            ps - SHACL.path - Iri("http://example.org/label")
            ps - SHACL.minCount - 1
        }
        val report = validator.validate(dataGraph, shapesGraph)
        assertTrue(report.isValid)
        assertTrue(report.validatedConstraints > 0)
    }

    @Test
    fun `providerId memory selects legacy validator`() {
        val validator = ValidatorRegistry.createValidator(
            ValidationConfig(profile = ValidationProfile.SHACL_CORE, providerId = "memory"),
        )
        assertTrue(validator is MemoryShaclValidator)
    }

    @Test
    fun `sh in constraint passes when value is allowed`() {
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
        val shapesGraph = Rdf.graph {
            val shape = Iri("http://example.org/S")
            val ps = Iri("http://example.org/ps")
            val listHead = bnode("list")
            val cellA = bnode("a")
            shape - RDF.type - SHACL.NodeShape
            shape - SHACL.targetNode - Iri("http://example.org/x")
            shape - SHACL.`property` - ps
            ps - RDF.type - SHACL.PropertyShape
            ps - SHACL.path - Iri("http://example.org/status")
            ps - SHACL.`in` - listHead
            listHead - RDF.first - Iri("http://example.org/on")
            listHead - RDF.rest - cellA
            cellA - RDF.first - Iri("http://example.org/off")
            cellA - RDF.rest - RDF.nil
        }
        val dataGraph = Rdf.graph {
            val x = Iri("http://example.org/x")
            x - Iri("http://example.org/status") - Iri("http://example.org/on")
        }
        val report = validator.validate(dataGraph, shapesGraph)
        assertTrue(report.isValid)
    }
}
