package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.native.OwlImportsExpander
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Round eight audit findings on the native engine: numeric bounds, imports, ill-formed parameters, statistics. */
class NativeRoundEightTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun validate(data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config).validate(g(data), g(shapes))

    // --- numeric bounds ------------------------------------------------------------------------------------------------

    @Test
    fun `a float is compared with a decimal bound as a float`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:p ; sh:maxInclusive 0.1 ; sh:minInclusive 0.1 ] ."
        // Widened to double, the float nearest to 0.1 is 0.100000001490116: above the bound.
        assertTrue(validate("""ex:a ex:p "0.1"^^xsd:float .""", shapes).isValid)
        assertTrue(validate("""ex:a ex:p "0.1"^^xsd:decimal , "0.1"^^xsd:double .""", shapes).isValid)
        assertFalse(validate("""ex:a ex:p "0.11"^^xsd:float .""", shapes).isValid)
    }

    @Test
    fun `negative zero equals zero and NaN satisfies no bound`() {
        val atLeastZero = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:p ; sh:minInclusive 0 ; sh:maxInclusive 0 ] ."
        assertTrue(validate("""ex:a ex:p "-0.0"^^xsd:double , "0.0"^^xsd:double , "-0"^^xsd:float , 0 .""", atLeastZero).isValid)
        val exclusive = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:p ; sh:minExclusive 0 ] ."
        assertEquals(listOf(ConstraintType.MIN_EXCLUSIVE), validate("""ex:a ex:p "-0.0"^^xsd:double .""", exclusive).violations.map { it.constraint.constraintType })
        val any = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:p ; sh:minInclusive \"-INF\"^^xsd:double ; sh:maxInclusive \"INF\"^^xsd:double ] ."
        assertTrue(validate("""ex:a ex:p 1 , "1.5"^^xsd:float .""", any).isValid)
        assertEquals(
            setOf(ConstraintType.MIN_INCLUSIVE, ConstraintType.MAX_INCLUSIVE),
            validate("""ex:a ex:p "NaN"^^xsd:double .""", any).violations.map { it.constraint.constraintType }.toSet(),
        )
        // sh:lessThanOrEquals between the two zeros, both ways.
        val ordered = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:p ; sh:lessThanOrEquals ex:q ] , [ sh:path ex:q ; sh:lessThanOrEquals ex:p ] ."
        assertTrue(validate("""ex:a ex:p "-0.0"^^xsd:double ; ex:q "0.0"^^xsd:double .""", ordered).isValid)
    }

    @Test
    fun `sh in and sh hasValue keep comparing terms and not values`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:p ; sh:in ( 0 ) ] ."
        assertFalse(validate("""ex:a ex:p "-0.0"^^xsd:double .""", shapes).isValid)
        assertFalse(validate("""ex:a ex:p "0.0"^^xsd:decimal .""", shapes).isValid)
        assertTrue(validate("ex:a ex:p 0 .", shapes).isValid)
    }

    // --- owl:imports ---------------------------------------------------------------------------------------------------

    @Test
    fun `shapes over the triple budget fail with the domain exception`() {
        val root = g("ex:a ex:p 1 , 2 , 3 . ex:Root owl:imports ex:Other .")
        val other = g("ex:b ex:p 1 , 2 , 3 .")
        val imports = ImportConfig(resolveOwlImports = true)
        // The root graph alone is over the budget.
        val tooBig = assertThrows(ShaclValidationException::class.java) { OwlImportsExpander.expand(root, imports, emptyMap(), maxTriples = 2) }
        assertTrue(tooBig.message.orEmpty().contains("maxCombinedGraphTriples"), tooBig.message)
        // The root fits, the root and its import together do not.
        val expanded = assertThrows(ShaclValidationException::class.java) {
            OwlImportsExpander.expand(root, imports, mapOf(ex("Other") to other), maxTriples = 5)
        }
        assertTrue(expanded.message.orEmpty().contains("maxCombinedGraphTriples"), expanded.message)
        assertEquals(7, OwlImportsExpander.expand(root, imports, mapOf(ex("Other") to other), maxTriples = 7).size())
    }

    private val importing = """
        ex:Shapes a owl:Ontology ; owl:imports ex:Missing , ex:Present .
        ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
    """
    private val present = "ex:T a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:q ; sh:minCount 1 ] ."

    private fun importConfig(includeWarnings: Boolean = true) = ValidationConfig(
        includeWarnings = includeWarnings,
        imports = ImportConfig(resolveOwlImports = true),
        dataset = DatasetValidationConfig(auxiliaryGraphs = mapOf(ex("Present") to g(present))),
    )

    @Test
    fun `an import that cannot be resolved is reported as a warning`() {
        val report = NativeShaclValidator(importConfig()).validate(g("ex:a ex:p 1 ; ex:q 1 ."), g(importing))
        // The resolved import is applied, the unresolved one is skipped: the report conforms and says what was skipped.
        assertTrue(report.isValid, report.violations.toString())
        val warning = report.warnings.single()
        assertTrue(warning.message.contains("owl:imports") && warning.message.contains(ex("Missing").value), warning.message)
        assertFalse(warning.message.contains(ex("Present").value), warning.message)
        val exported = report.toShaclValidationReportRdf().getTriples().filter { it.predicate == KastorShaclVocabulary.warning }
        assertEquals(listOf(warning.message), exported.map { (it.obj as Literal).lexical })
        // The imported shape really is validated.
        assertFalse(NativeShaclValidator(importConfig()).validate(g("ex:a ex:p 1 ."), g(importing)).isValid)
    }

    @Test
    fun `import warnings follow includeWarnings and are absent when imports are not resolved`() {
        val silent = NativeShaclValidator(importConfig(includeWarnings = false)).validate(g("ex:a ex:p 1 ; ex:q 1 ."), g(importing))
        assertTrue(silent.warnings.isEmpty(), silent.warnings.toString())
        val notResolving = NativeShaclValidator(ValidationConfig()).validate(g("ex:a ex:p 1 ."), g(importing))
        assertTrue(notResolving.warnings.isEmpty(), notResolving.warnings.toString())
    }

    @Test
    fun `an import beyond the import depth is reported as a warning`() {
        val config = ValidationConfig(
            imports = ImportConfig(resolveOwlImports = true, maxImportDepth = 1),
            dataset = DatasetValidationConfig(
                auxiliaryGraphs = mapOf(ex("One") to g("ex:One owl:imports ex:Two ."), ex("Two") to g(present)),
            ),
        )
        val report = NativeShaclValidator(config).validate(g("ex:a ex:p 1 ."), g("ex:Root owl:imports ex:One ."))
        assertTrue(report.isValid)
        val warning = report.warnings.single()
        assertTrue(warning.message.contains(ex("Two").value) && warning.message.contains("maxImportDepth"), warning.message)
    }

    // --- ill-formed parameter values -----------------------------------------------------------------------------------

    private fun assertIllFormed(shapes: String, vararg expected: String) {
        val error = assertThrows(ShaclValidationException::class.java) { validate("ex:a ex:p 1 .", shapes) }
        assertTrue(error.cause is ShapeCompileException, "cause: ${error.cause}")
        for (part in expected) assertTrue(error.message.orEmpty().contains(part), "expected \"$part\" in: ${error.message}")
    }

    @Test
    fun `a literal sh class or sh nodeKind value is an ill-formed shapes graph`() {
        assertIllFormed("""ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:class "Person" .""", "sh:class", "Person")
        assertIllFormed("""ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:class 42 ] .""", "sh:class", "42")
        assertIllFormed("""ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeKind "IRI" .""", "sh:nodeKind", "IRI")
        assertIllFormed("""ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:nodeKind true ] .""", "sh:nodeKind")
        // Well-formed values still compile.
        assertFalse(validate("ex:a ex:p 1 .", "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:class ex:Person ; sh:nodeKind sh:IRI .").isValid)
    }

    // --- statistics ----------------------------------------------------------------------------------------------------

    @Test
    fun `statistics count the validated focus nodes and average the real validation time`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            ex:U a sh:NodeShape ; sh:targetNode ex:a , ex:z ; sh:property [ sh:path ex:q ; sh:maxCount 1 ] .
        """
        val data = "ex:a a ex:T ; ex:p 1 . ex:b a ex:T ; ex:p 1 . ex:c a ex:T . ex:other ex:p 1 ."
        val report = validate(data, shapes)
        assertEquals(listOf(ex("c")), report.violations.map { it.focusNode })
        // a, b, c (targets of S) and a, z (targets of U): four distinct focus nodes, one of them with a result.
        assertEquals(4, report.statistics.validatedResources)
        assertEquals(report.validationTime.dividedBy(4), report.statistics.averageValidationTimePerResource)

        val nothing = validate("ex:other ex:p 1 .", "ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .")
        assertEquals(0, nothing.statistics.validatedResources)
        assertEquals(Duration.ZERO, nothing.statistics.averageValidationTimePerResource)

        val single = NativeShaclValidator(ValidationConfig()).validateResource(g(data), g(shapes), ex("c"))
        assertEquals(1, single.statistics.validatedResources)
    }

    // --- sh:closed sh:ByTypes ------------------------------------------------------------------------------------------

    @Test
    fun `a deep subclass chain under closed by types does not overflow the stack`() {
        val depth = 50_000
        val shapes = Rdf.graph {
            for (i in 0 until depth) {
                ex("C$i") - RDF.type - RDFS.Class
                if (i + 1 < depth) ex("C$i") - RDFS.subClassOf - ex("C${i + 1}")
            }
            // A cycle at the top of the chain must not loop either.
            ex("C${depth - 1}") - RDFS.subClassOf - ex("C0")
            ex("S") - RDF.type - SHACL.NodeShape
            ex("S") - SHACL.targetNode - ex("a")
            ex("S") - SHACL.closed - SHACL.ByTypes
            ex("S") - SHACL.`property` - ex("SP")
            ex("SP") - SHACL.path - ex("p")
            ex("Top") - RDF.type - SHACL.NodeShape
            ex("Top") - SHACL.targetClass - ex("C${depth - 1}")
            ex("Top") - SHACL.`property` - ex("TopQ")
            ex("TopQ") - SHACL.path - ex("q")
        }
        val data = g("ex:a a ex:C0 ; ex:q 2 ; ex:r 3 .")
        val report = NativeShaclValidator(ValidationConfig()).validate(data, shapes)
        // ex:q is declared by the shape that targets the top of the chain; ex:r by none; ex:p belongs to a shape that
        // targets no type of ex:a.
        val closed = report.violations.filter { it.constraint.constraintType == ConstraintType.CLOSED }
        assertEquals(listOf(ex("r").value), closed.map { it.constraint.path }, report.violations.toString())
    }

    // --- vocabulary ----------------------------------------------------------------------------------------------------

    @Test
    fun `the vocabulary document states its version and versioning policy`() {
        val stream = KastorShaclVocabulary::class.java.getResourceAsStream(KastorShaclVocabulary.VOCABULARY_RESOURCE)!!
        val document = Rdf.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) }, RdfFormat.TURTLE).getTriples()
        val ontology = Iri(KastorShaclVocabulary.NAMESPACE.removeSuffix("#"))
        val version = document.filter { it.subject == ontology && it.predicate == OWL.versionInfo }.map { (it.obj as Literal).lexical }
        assertEquals(listOf(KastorShaclVocabulary.VERSION), version, "the ontology must have one owl:versionInfo, the declared version")
        assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(version.single()), version.toString())
        val notes = document.filter { it.subject == ontology && it.predicate == Iri("http://www.w3.org/2004/02/skos/core#historyNote") }
            .map { (it.obj as Literal).lexical }
        assertTrue(notes.any { it.contains("namespace") && it.contains("added") }, "versioning note: $notes")
    }
}
