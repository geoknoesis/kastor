package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Offline semantic coverage must not depend on the optional upstream SHACL corpus. */
class NativeTermConstraintMatrixTest {
    private val focus = Iri("urn:focus")
    private val predicate = Iri("urn:p")
    private val iri = Iri("urn:value")
    private val blank = BlankNode("value")
    private val plain = string("plain")
    private val english = LangString("English", "en")
    private val regional = LangString("Regional", "en-GB")
    private val french = LangString("French", "fr")
    private val directed = LangString("Directed", "en", Direction.LTR)
    private val triple = TripleTerm(RdfTriple(iri, predicate, plain))
    private val terms = listOf(iri, blank, plain, english, regional, french, directed, triple)

    private fun validate(values: List<RdfTerm>, constraint: Iri, parameter: RdfTerm,
                         extra: List<RdfTriple> = emptyList()): ValidationReport {
        val shape = Iri("urn:shape")
        val property = Iri("urn:property")
        val shapes = MemoryGraph(listOf(
            RdfTriple(shape, RDF.type, SHACL.NodeShape),
            RdfTriple(shape, SHACL.targetNode, focus),
            RdfTriple(shape, SHACL.property, property),
            RdfTriple(property, SHACL.path, predicate),
            RdfTriple(property, constraint, parameter),
        ) + extra)
        return NativeShaclValidatorProvider().createValidator(ValidationConfig.default()).validate(
            MemoryGraph(values.map { RdfTriple(focus, predicate, it) }), shapes)
    }

    @Test fun `node kinds distinguish resources literals and quoted triples`() {
        val accepted = mapOf(
            SHACL.IRI to setOf(iri),
            SHACL.BlankNode to setOf(blank),
            SHACL.Literal to setOf(plain, english, regional, french, directed),
            SHACL.BlankNodeOrIRI to setOf(iri, blank),
            SHACL.BlankNodeOrLiteral to setOf(blank, plain, english, regional, french, directed),
            SHACL.IRIOrLiteral to setOf(iri, plain, english, regional, french, directed),
            SHACL.TripleTerm to setOf(triple),
        )
        for ((kind, valid) in accepted) {
            val report = validate(terms, SHACL.nodeKind, kind)
            assertEquals((terms.toSet() - valid), report.violations.map { it.value }.toSet(), kind.value)
            assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.NODE_KIND })
            assertTrue(report.warnings.isEmpty())
        }
    }

    @Test fun `language ranges are case insensitive and preserve regional boundaries`() {
        val accepted = mapOf(
            "*" to setOf(english, regional, french, directed),
            "EN" to setOf(english, regional, directed),
            "en-*" to setOf(english, regional, directed),
            "en-gb" to setOf(regional),
            "de" to emptySet(),
        )
        for ((range, valid) in accepted) {
            val head = BlankNode("languages")
            val report = validate(terms, SHACL.languageIn, head, listOf(
                RdfTriple(head, RDF.first, string(range)), RdfTriple(head, RDF.rest, RDF.nil)))
            assertEquals(terms.toSet() - valid, report.violations.map { it.value }.toSet(), range)
            assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.LANGUAGE_IN })
        }
    }

    @Test fun `datatype constraints distinguish directional language and ill typed values`() {
        val integer = Literal("7", XSD.integer)
        val invalidInteger = Literal("bad", XSD.integer)
        val values = terms + listOf(integer, invalidInteger)
        val accepted = mapOf(
            XSD.string to setOf(plain), XSD.integer to setOf(integer),
            RDF.langString to setOf(english, regional, french),
            RDF.dirLangString to setOf(directed),
        )
        for ((datatype, valid) in accepted) {
            val report = validate(values, SHACL.datatype, datatype)
            assertEquals(values.toSet() - valid, report.violations.map { it.value }.toSet(), datatype.value)
            assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.DATATYPE })
        }
    }

    @Test fun `unique language compares tags without conflating text directions`() {
        val rtl = LangString("RTL", "en", Direction.RTL)
        val values = listOf(english, regional, directed, rtl)
        assertTrue(validate(values, SHACL.uniqueLang, TrueLiteral).isValid)
        val duplicate = validate(values + LangString("Other", "EN"), SHACL.uniqueLang, TrueLiteral)
        assertEquals(1, duplicate.violations.size)
        assertEquals(ConstraintType.UNIQUE_LANG, duplicate.violations.single().constraint.constraintType)
    }
}
