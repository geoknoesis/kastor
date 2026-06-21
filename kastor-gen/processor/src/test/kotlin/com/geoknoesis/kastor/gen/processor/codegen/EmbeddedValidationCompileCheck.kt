package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.RdfHandle
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Compile-time verification that the value-constraint code emitted by
 * [com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator]'s
 * embedded `validate()` is valid Kotlin against the real runtime + vocabulary types.
 *
 * The string-assertion tests in OntologyWrapperGeneratorTest prove the generator emits
 * exactly these tokens; this file proves those tokens type-check — notably the backticked
 * `SHACL.`in`` member and the numeric/length/pattern blocks. If a SHACL member name were
 * wrong (e.g. a typo'd `SHACL.maxInclusive`), this would fail to compile.
 *
 * The body mirrors the generated `validate()`; it is a compile target, not executed.
 */
@Suppress("unused")
internal object EmbeddedValidationCompileCheck {
    fun validateLike(rdf: RdfHandle): ValidationResult {
        val violations = mutableListOf<ShaclViolation>()

        // sh:pattern
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("http://example.org/id")).forEach { lit ->
            if (!Regex("DOC-[0-9]+").containsMatchIn(lit.lexical)) violations.add(ShaclViolation(
                focusNode = rdf.node as RdfResource,
                shapeIri = SHACL.NodeShape,
                constraintIri = SHACL.pattern,
                path = Iri("http://example.org/id"),
                message = "pattern DOC-[0-9]+ violated"
            ))
        }

        // sh:minLength / sh:maxLength
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("http://example.org/title")).forEach { lit ->
            if (lit.lexical.length < 2) violations.add(ShaclViolation(
                focusNode = rdf.node as RdfResource,
                shapeIri = SHACL.NodeShape,
                constraintIri = SHACL.minLength,
                path = Iri("http://example.org/title"),
                message = "minLength 2 violated"
            ))
            if (lit.lexical.length > 20) violations.add(ShaclViolation(
                focusNode = rdf.node as RdfResource,
                shapeIri = SHACL.NodeShape,
                constraintIri = SHACL.maxLength,
                path = Iri("http://example.org/title"),
                message = "maxLength 20 violated"
            ))
        }

        // sh:minInclusive / sh:maxInclusive / sh:minExclusive / sh:maxExclusive
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("http://example.org/score")).forEach { lit ->
            val num = lit.lexical.toDoubleOrNull()
            if (num != null) {
                if (num < 0.0) violations.add(ShaclViolation(
                    focusNode = rdf.node as RdfResource,
                    shapeIri = SHACL.NodeShape,
                    constraintIri = SHACL.minInclusive,
                    path = Iri("http://example.org/score"),
                    message = "minInclusive 0.0 violated"
                ))
                if (num > 1000.0) violations.add(ShaclViolation(
                    focusNode = rdf.node as RdfResource,
                    shapeIri = SHACL.NodeShape,
                    constraintIri = SHACL.maxInclusive,
                    path = Iri("http://example.org/score"),
                    message = "maxInclusive 1000.0 violated"
                ))
                if (num <= 0.0) violations.add(ShaclViolation(
                    focusNode = rdf.node as RdfResource,
                    shapeIri = SHACL.NodeShape,
                    constraintIri = SHACL.minExclusive,
                    path = Iri("http://example.org/score"),
                    message = "minExclusive 0.0 violated"
                ))
                if (num >= 100.0) violations.add(ShaclViolation(
                    focusNode = rdf.node as RdfResource,
                    shapeIri = SHACL.NodeShape,
                    constraintIri = SHACL.maxExclusive,
                    path = Iri("http://example.org/score"),
                    message = "maxExclusive 100.0 violated"
                ))
            }
        }

        // sh:in
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("http://example.org/status")).forEach { lit ->
            if (lit.lexical !in listOf("DRAFT", "ACTIVE")) violations.add(ShaclViolation(
                focusNode = rdf.node as RdfResource,
                shapeIri = SHACL.NodeShape,
                constraintIri = SHACL.`in`,
                path = Iri("http://example.org/status"),
                message = "in violated"
            ))
        }

        return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)
    }
}

class EmbeddedValidationCompileCheckTest {
    @Test
    fun `generated value-constraint constructs type-check against runtime types`() {
        // Compilation of EmbeddedValidationCompileCheck IS the assertion; this guards
        // against the object being dropped as dead code.
        assertNotNull(EmbeddedValidationCompileCheck)
    }
}
