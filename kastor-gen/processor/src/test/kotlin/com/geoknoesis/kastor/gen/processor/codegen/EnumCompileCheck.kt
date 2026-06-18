package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.RdfHandle
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

// ── Area 1: IRI enum sealed type (mirrors EnumGenerator output for IRI-membered enum) ──────────

/**
 * Compile-time mirror of an IRI-membered generated sealed enum exactly as [EnumGenerator] emits it.
 * Proves the sealed interface + Known enum + Unknown data class + companion `from` all type-check.
 */
internal sealed interface DocumentStatusCC {
    val iri: Iri
    enum class Known(override val iri: Iri) : DocumentStatusCC {
        DRAFT(Iri("https://ex/#DRAFT")),
        ACTIVE(Iri("https://ex/#ACTIVE")),
    }
    data class Unknown(override val iri: Iri) : DocumentStatusCC
    companion object {
        fun from(iri: Iri): DocumentStatusCC =
            Known.entries.firstOrNull { it.iri == iri } ?: Unknown(iri)
    }
}

// ── Area 2: LITERAL enum sealed type (mirrors EnumGenerator output for literal-membered enum) ──

/**
 * Compile-time mirror of a LITERAL-membered generated sealed enum:
 * `val code: String`, `from(code: String)`.
 */
internal sealed interface ReviewStateCC {
    val code: String
    enum class Known(override val code: String) : ReviewStateCC {
        PENDING("pending"),
        APPROVED("approved"),
        REJECTED("rejected"),
    }
    data class Unknown(override val code: String) : ReviewStateCC
    companion object {
        fun from(code: String): ReviewStateCC =
            Known.entries.firstOrNull { it.code == code } ?: Unknown(code)
    }
}

// ── Areas 3, 4, 5: Wrapper read, IRI sh:in validation, and writer enum write ────────────────────

/**
 * Compile-time verification that the enum-related code emitted by
 * [com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator] and
 * [com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator] is valid
 * Kotlin against the real runtime + vocabulary + rdf types.
 *
 * Area 3 — wrapper IRI read: mirrors `generateEnumPropertyInitializer` for IRI kind.
 * Area 4 — IRI sh:in validation: mirrors the `inValuesTyped` block (the `{ it as Iri }` fix).
 * Area 5 — writer enum write: mirrors `buildEnumWrite` for both IRI and LITERAL kinds.
 *
 * The body mirrors the generated code; it is a compile target, not executed.
 */
@Suppress("unused")
internal object EnumCompileCheck {

    // ── Area 3: wrapper IRI enum read ────────────────────────────────────────────────────────────
    //
    // Mirrors: generateEnumPropertyInitializer (IRI kind, optional single)
    //   KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri("…")) { child ->
    //     DocumentStatus.from(child as Iri)
    //   }.firstOrNull()

    fun readIriEnum(rdf: RdfHandle): DocumentStatusCC? =
        KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri("https://ex/#status")) { child ->
            DocumentStatusCC.from(child as Iri)
        }.firstOrNull()

    // Mirrors: generateEnumPropertyInitializer (LITERAL kind, optional single)
    //   KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("…")).map { ReviewState.from(it.lexical) }.firstOrNull()

    fun readLiteralEnum(rdf: RdfHandle): ReviewStateCC? =
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("https://ex/#reviewState"))
            .map { ReviewStateCC.from(it.lexical) }
            .firstOrNull()

    // ── Area 4: IRI sh:in validation (the critical { it as Iri } fix from Task 7) ──────────────
    //
    // Mirrors: generateEmbeddedValidation → inValuesTyped block:
    //   KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri("…")) { it as Iri }.forEach { obj ->
    //     if (obj !in listOf(Iri("…"), Iri("…"))) violations.add(ShaclViolation(…))
    //   }

    fun validateIriInLike(rdf: RdfHandle): ValidationResult {
        val violations = mutableListOf<ShaclViolation>()

        KastorGraphOps.getObjectValues(
            rdf.graph,
            rdf.node,
            Iri("https://ex/#status")
        ) { it as Iri }.forEach { obj ->
            if (obj !in listOf(
                    Iri("https://ex/#DRAFT"),
                    Iri("https://ex/#ACTIVE")
                )
            ) violations.add(
                ShaclViolation(
                    focusNode = rdf.node as RdfResource,
                    shapeIri = SHACL.NodeShape,
                    constraintIri = SHACL.`in`,
                    path = Iri("https://ex/#status"),
                    message = "in violated"
                )
            )
        }

        return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)
    }

    // ── Area 5: writer enum write ─────────────────────────────────────────────────────────────
    //
    // IRI enum (optional single): record.status?.let { triples += RdfTriple(subject, Iri("pred"), it.iri) }
    // LITERAL enum (list):        record.codes.forEach { triples += RdfTriple(subject, Iri("pred"), Literal(it.code)) }

    fun writeIriEnumOptional(
        subject: Iri,
        statusValue: DocumentStatusCC?,
    ): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        statusValue?.let { triples += RdfTriple(subject, Iri("https://ex/#status"), it.iri) }
        return triples
    }

    fun writeIriEnumRequired(
        subject: Iri,
        statusValue: DocumentStatusCC,
    ): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        triples += RdfTriple(subject, Iri("https://ex/#status"), statusValue.iri)
        return triples
    }

    fun writeLiteralEnumList(
        subject: Iri,
        codes: List<ReviewStateCC>,
    ): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        codes.forEach { triples += RdfTriple(subject, Iri("https://ex/#reviewState"), Literal(it.code)) }
        return triples
    }

    fun writeLiteralEnumRequired(
        subject: Iri,
        state: ReviewStateCC,
    ): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        triples += RdfTriple(subject, Iri("https://ex/#reviewState"), Literal(state.code))
        return triples
    }
}

class EnumCompileCheckTest {
    @Test
    fun `generated enum shape and usage type-check`() {
        // Compilation of EnumCompileCheck IS the assertion; this guards
        // against the object being dropped as dead code.
        assertNotNull(EnumCompileCheck)
    }
}
