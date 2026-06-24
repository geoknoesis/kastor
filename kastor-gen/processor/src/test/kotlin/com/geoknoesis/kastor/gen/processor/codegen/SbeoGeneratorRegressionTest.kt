package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the SBEO failures:
 *  - Bug 1: `sh:hasValue <iri>` (combination-matrix pins) made the parser throw LiteralRequiredException
 *    and abort all remaining shapes (ShaclParser).
 *  - Bug 3: referenced domain types were emitted with an empty package -> invalid `import InformationItem`
 *    (TypeMapper / InterfaceGenerator).
 */
class SbeoGeneratorRegressionTest {

    private val logger = object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    private val emptyContext = JsonLdContext(
        prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap(),
    )

    @Test
    fun `bug 1 - parser does not abort on IRI-valued sh-hasValue`() {
        // The CorroborativeShape pins directionalRelation/evidentialBasis with sh:hasValue <iri>; with the
        // bug, reading that value threw and lost every shape that followed.
        val shapes = ShaclParser(logger).parseShaclContent(
            """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix sbe: <https://w3id.org/def/sbevidence#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            sbe:BelievabilityShape a sh:NodeShape ; sh:targetClass sbe:Believability ;
                sh:property [ sh:path sbe:assessesItem ; sh:minCount 1 ; sh:class sbe:InformationItem ] .
            sbe:CorroborativeShape a sh:NodeShape ; sh:targetClass sbe:CorroborativeCombination ;
                sh:property [ sh:path sbe:directionalRelation ; sh:hasValue sbe:harmonious ] ;
                sh:property [ sh:path sbe:evidentialBasis ; sh:hasValue sbe:differentEvents ] .
            sbe:CumulativeShape a sh:NodeShape ; sh:targetClass sbe:CumulativeRedundancy ;
                sh:property [ sh:path sbe:combinesEvidence ; sh:minCount 2 ; sh:class sbe:InformationItem ] .
            """.trimIndent(),
        )

        val targets = shapes.map { it.targetClass.substringAfterLast('#') }.toSet()
        assertEquals(3, shapes.size, "all three shapes must survive parsing")
        assertTrue("CorroborativeCombination" in targets, "the sh:hasValue<iri> shape itself")
        assertTrue("CumulativeRedundancy" in targets, "a shape declared after it must not be lost")
        assertTrue("Believability" in targets)
    }

    @Test
    fun `bugs 2,3,4 - shaped refs are typed, unshaped sh-class targets fall back to IRI, imports valid`() {
        fun shape(name: String, target: String, props: List<ShaclProperty> = emptyList()) =
            ShaclShape("https://w3id.org/def/sbevidence#$name", target, props)
        fun objectProp(name: String, targetClass: String) = ShaclProperty(
            path = "https://w3id.org/def/sbevidence#$name", name = name, description = "",
            datatype = null, targetClass = targetClass, minCount = 1, maxCount = null,
        )
        val ns = "https://w3id.org/def/sbevidence#"

        // InformationItem IS shaped; Hypothesis is NOT (referenced by sh:class but has no NodeShape).
        val model = OntologyModel(
            listOf(
                shape("InformationItemShape", "${ns}InformationItem"),
                shape(
                    "EvidentialRelevanceShape", "${ns}EvidentialRelevance",
                    listOf(objectProp("relevantItem", "${ns}InformationItem"), objectProp("bearsOnHypothesis", "${ns}Hypothesis")),
                ),
            ),
            emptyContext,
        )
        val files = InterfaceGenerator(logger, ValidationAnnotations.NONE)
            .generateInterfaces(model, "com.geoknoesis.ia3.core.model", fallbackUnshapedToIri = true)
        val code = java.io.StringWriter().also { files["EvidentialRelevance"]!!.writeTo(it) }.toString()

        assertTrue(code.contains("relevantItem: List<InformationItem>"), "shaped target is typed (bug 3)")
        assertTrue(code.contains("bearsOnHypothesis: List<String>"), "unshaped sh:class target falls back to IRI (bug 4)")
        assertFalse(
            code.lineSequence().any { it.trim() == "import InformationItem" },
            "no invalid default-package import (bug 3)",
        )
        assertFalse(code.contains("import jakarta.validation.constraints\n"), "no invalid jakarta package import (bug 2)")
    }
}
