package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class ReleaseRegressionTest {
    @Test fun `resource validation counts only evaluated focus nodes`() {
        val data = graph("<urn:a> <urn:p> 1 . <urn:b> <urn:p> 2 .")
        val shapes = graph(prefix + "<urn:S> a sh:NodeShape ; sh:targetNode <urn:a>, <urn:b> ; sh:nodeKind sh:IRI .")
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig())
        assertEquals(2, validator.validate(data, shapes).validatedConstraints)
        assertEquals(1, validator.validateResource(data, shapes, Iri("urn:a")).validatedConstraints)
    }
    private fun graph(text: String) = Rdf.parse(text, RdfFormat.TURTLE)
    private val prefix = "@prefix sh: <http://www.w3.org/ns/shacl#> . "
    @Test fun `resource validation keeps multi-hop context`() {
        val data = graph("<urn:a> <urn:p> <urn:b> . <urn:b> <urn:q> 'ok' .")
        val shapes = graph(prefix + "<urn:S> a sh:NodeShape ; sh:targetNode <urn:a> ; sh:property [ sh:path (<urn:p> <urn:q>) ; sh:minCount 1 ] .")
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig())
        assertTrue(validator.validateResource(data, shapes, Iri("urn:a")).isValid)
    }
    @Test fun `warnings cannot hide later errors behind result cap`() {
        val data = graph("<urn:a> <urn:p> 1 .")
        val shapes = graph(prefix + "<urn:A> a sh:NodeShape ; sh:targetNode <urn:a> ; sh:property [ sh:path <urn:missing> ; sh:minCount 1 ; sh:severity sh:Warning ] . <urn:Z> a sh:NodeShape ; sh:targetNode <urn:a> ; sh:property [ sh:path <urn:missing2> ; sh:minCount 1 ] .")
        val result = NativeShaclValidatorProvider().createValidator(ValidationConfig(maxViolations = 1)).validate(data, shapes)
        assertFalse(result.isValid)
        assertTrue(result.violationsTruncated)
        assertEquals(1, result.violations.size)
    }
    @Test fun `tiny deadline and unsupported execution modes fail explicitly`() {
        val data = graph("<urn:a> <urn:p> 1 .")
        assertThrows(ShaclValidationException::class.java) {
            NativeShaclValidatorProvider().createValidator(ValidationConfig(timeout = Duration.ofNanos(1))).validate(data, data)
        }
        assertThrows(IllegalArgumentException::class.java) { NativeShaclValidatorProvider().createValidator(ValidationConfig(parallelValidation = true)) }
    }
    @Test fun `version tags are local to a validator`() {
        val config = ValidationConfig(cache = CacheConfig(shapesGraphVersion = "v1"))
        NativeShaclValidatorProvider().createValidator(config).validate(graph(""), graph(prefix + "<urn:A> a sh:NodeShape ."))
        NativeShaclValidatorProvider().createValidator(config).validate(graph(""), graph(prefix + "<urn:B> a sh:NodeShape ."))
    }
}
