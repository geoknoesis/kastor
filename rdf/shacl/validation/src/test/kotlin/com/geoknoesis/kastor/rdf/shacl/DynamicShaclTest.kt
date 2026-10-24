package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SHACL 1.2 Node Expressions §8 (Dynamic SHACL): constraint parameters computed by a node expression, evaluated in the
 * data graph with the focus node of the shape, per focus node.
 */
class DynamicShaclTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.com/ns#$local")
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config) { repository(provider) }.validate(g(data), g(shapes))

    /** The shapes graph validated against itself, as the W3C cases do. */
    @Suppress("unused")
    private fun validateSelf(provider: String, ttl: String) = g(ttl).let { NativeShaclValidator(ValidationConfig.default()) { repository(provider) }.validate(it, it) }

    private fun unsupported(error: Throwable): Set<UnsupportedShaclFeature> =
        generateSequence(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first().features

    /** A property shape on ex:path ex:v with [body], for the focus node ex:a. */
    private fun prop(body: String) = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:v ; $body ] ."

    // --- §8.1 -----------------------------------------------------------------------------------------------------------

    private val presidents = """
        ex:PresidentShape
            a sh:NodeShape ;
            sh:targetClass ex:President ;
            sh:property ex:PresidentShape-age ;
        .
        ex:PresidentShape-age
            a sh:PropertyShape ;
            sh:path ex:age ;
            sh:minInclusive [
                shnex:if [
                    sparql:equals (
                        [ shnex:pathValues ex:country ]
                        ex:USA
                    )
                ] ;
                shnex:then 35 ;
                shnex:else 18 ;
            ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `spec example 31 computes the minimum age of presidents`(provider: String) {
        val data = """
            ex:Old a ex:President ; ex:age 40 ; ex:country ex:USA .
            ex:Young a ex:President ; ex:age 34 ; ex:country ex:USA .
            ex:Fr a ex:President ; ex:age 20 ; ex:country ex:France .
            ex:Kid a ex:President ; ex:age 17 ; ex:country ex:France .
            ex:None a ex:President ; ex:age 17 .
        """
        val report = validate(provider, data, presidents)
        assertEquals(mapOf(ex("Young") to int(34), ex("Kid") to int(17), ex("None") to int(17)), report.violations.associate { it.focusNode to it.value })
        assertEquals(3, report.violations.size)
        assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.MIN_INCLUSIVE }, report.violations.toString())
    }

    // --- evaluation context (D5) ----------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic parameters read the focus node, not the value nodes`(provider: String) {
        val data = "ex:f ex:maxItems 1 ; ex:item ex:i1 , ex:i2 . ex:i1 ex:maxItems 5 . ex:i2 ex:maxItems 5 ."
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:f ; sh:property [ sh:path ex:item ; sh:maxCount [ shnex:pathValues ex:maxItems ] ] ."
        val v = validate(provider, data, shapes).violations.single()
        assertEquals(ex("f"), v.focusNode)
        assertEquals(ConstraintType.MAX_COUNT, v.constraint.constraintType)
        // The scope is empty: the variable "value" is unbound, so the parameter has no output node.
        val byValue = "ex:S a sh:NodeShape ; sh:targetNode ex:f ; sh:property [ sh:path ex:item ; sh:maxCount [ shnex:var \"value\" ] ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, data, byValue) }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "sh:maxCount" in message && "no nodes" in message, message)
    }

    // --- output rules (D6) ----------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `zero output nodes for a single-node parameter fail`(provider: String) {
        val error = assertThrows(ShaclValidationException::class.java) {
            validate(provider, "ex:a ex:v 1 .", prop("sh:minCount [ shnex:pathValues ex:min ]"))
        }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "sh:minCount" in message && "no nodes" in message, message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `several output nodes for a single-node parameter fail`(provider: String) {
        val error = assertThrows(ShaclValidationException::class.java) {
            validate(provider, "ex:a ex:v 40 ; ex:minAge 18 , 35 .", prop("sh:minInclusive [ shnex:pathValues ex:minAge ]"))
        }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "2 nodes" in message, message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `ill-typed outputs fail`(provider: String) {
        val cases = listOf(
            "sh:minCount" to "\"abc\"",
            "sh:minCount" to "-1",
            "sh:minLength" to "ex:iri",
            "sh:minInclusive" to "ex:iri",
            "sh:uniqueLang" to "\"yes\"",
            "sh:maxListLength" to "1.5",
        )
        for ((parameter, bad) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, {
                validate(provider, "ex:a ex:v 1 ; ex:p $bad .", prop("$parameter [ shnex:pathValues ex:p ]"))
            }, "$parameter $bad")
            assertTrue("Dynamic SHACL" in error.message.orEmpty(), error.message)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a dynamic failure inside sh not stays a failure`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:not [ sh:property [ sh:path ex:p ; sh:minCount [ shnex:pathValues ex:none ] ] ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", shapes) }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "no nodes" in message, message)
        assertTrue(generateSequence<Throwable>(error) { it.cause }.none { it is ShapeCompileException }, error.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a dynamic parameter on a node shape reads the focus node`(provider: String) {
        val inclusive = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:maxInclusive [ shnex:pathValues ex:lim ] ."
        val v = validate(provider, "ex:a ex:lim 3 .", inclusive).violations.single()
        assertEquals(ex("a") to ex("a"), v.focusNode to v.value)
        assertEquals(ConstraintType.MAX_INCLUSIVE, v.constraint.constraintType)
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:b ex:lim 3 .", inclusive) }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "sh:maxInclusive" in message && "no nodes" in message, message)
        // The IRIs ex:a and ex:b are 23 characters long: the limit read from each focus node decides.
        val length = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:maxLength [ shnex:pathValues ex:lim ] ."
        val report = validate(provider, "ex:a ex:lim 100 . ex:b ex:lim 5 .", length)
        assertEquals(listOf(ex("b") to ConstraintType.MAX_LENGTH), report.violations.map { it.focusNode to it.constraint.constraintType })
    }

    // --- shape closure, recursion and detection -------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a parameter expression's shapes are compiled`(provider: String) {
        // ex:Big is reachable only from the expression at sh:maxCount: every part must be big.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:c ; sh:property [ sh:path ex:part ;
              sh:maxCount [ shnex:count [ shnex:filterShape ex:Big ; shnex:nodes [ shnex:pathValues ex:part ] ] ] ] .
            ex:Big sh:property [ sh:path ex:size ; sh:minInclusive 10 ] .
        """
        val data = "ex:a ex:part ex:p1 , ex:p2 . ex:c ex:part ex:p1 , ex:p3 . ex:p1 ex:size 20 . ex:p2 ex:size 30 . ex:p3 ex:size 1 ."
        val v = validate(provider, data, shapes).violations.single()
        assertEquals(ex("c") to ConstraintType.MAX_COUNT, v.focusNode to v.constraint.constraintType)
    }

    /** ex:S counts the ex:p values that conform to ex:S itself: a non-monotone read of ex:S (R16). */
    private val selfCount = """
        ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ;
          sh:minCount [ shnex:count [ shnex:filterShape ex:S ; shnex:nodes [ shnex:pathValues ex:p ] ] ] ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a data cycle through a parameter expression is a failure`(provider: String) {
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p ex:a .", selfCount) }
        assertTrue("undefined" in error.message.orEmpty(), error.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an acyclic recursive read through a parameter expression is decided`(provider: String) {
        // ex:b has no ex:p, so it conforms; ex:a has one value, ex:b, so sh:minCount 1 holds.
        assertTrue(validate(provider, "ex:a ex:p ex:b .", selfCount).violations.isEmpty())
        // Every ex:p value must conform to ex:S2 (sh:maxCount = the number that do): ex:c fails ex:bad, so ex:b and
        // then ex:a fail.
        val allConform = """
            ex:S2 a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:bad ; sh:maxCount 0 ] ;
              sh:property [ sh:path ex:p ; sh:maxCount [ shnex:count [ shnex:filterShape ex:S2 ; shnex:nodes [ shnex:pathValues ex:p ] ] ] ] .
        """
        val report = validate(provider, "ex:a ex:p ex:b . ex:b ex:p ex:c . ex:c ex:bad 1 .", allConform)
        assertTrue(report.violations.none { it.isUndefinedRecursion }, report.violations.toString())
        assertEquals(listOf(ex("a") to ConstraintType.MAX_COUNT), report.violations.map { it.focusNode to it.constraint.constraintType })
        assertTrue(validate(provider, "ex:a ex:p ex:b . ex:b ex:p ex:c .", allConform).violations.isEmpty())
    }

    @Test
    fun `an unsupported feature inside a shape reached only through a parameter expression is detected`() {
        val shapes = prop("sh:minCount [ shnex:count [ shnex:filterShape ex:Hidden ; shnex:nodes [ shnex:pathValues ex:v ] ] ]") +
            "\nex:Hidden sh:target [ a ex:CustomTarget ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.CUSTOM_TARGET), unsupported(error))
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:v 1 .", shapes, ignore)
        assertTrue(report.warnings.any { "Unsupported SHACL feature ignored" in it.message && "sh:target" in it.message }, report.warnings.toString())
    }

    @Test
    fun `a call of an undeclared function at a dynamic parameter is an unsupported feature`() {
        val shapes = prop("sh:minCount [ ex:undeclared ( 1 ) ]")
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported(error))
        assertTrue("sh:minCount" in error.message.orEmpty(), error.message)
        // Ignored, the parameter has no constraint.
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:b ex:v 1 .", shapes, ignore)
        assertTrue(report.warnings.any { "Unsupported SHACL feature ignored" in it.message && "sh:minCount" in it.message }, report.warnings.toString())
        assertTrue(report.violations.isEmpty(), report.violations.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a dynamic parameter in a recursive shape reports provisional answers as undefined, not violations`(provider: String) {
        // ex:S is recursive (sh:node through ex:p, and the expression's read of ex:T, which is ex:S). While the solver
        // iterates, the conformance of ex:b and ex:c is provisional: the dynamic sh:maxCount must not turn it into a
        // violation. Decided: ex:b conforms, ex:c fails ex:S (ex:bad), so one of the two ex:q values is a T: maxCount 1.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:node ex:S ] ;
              sh:property [ sh:path ex:bad ; sh:maxCount 0 ] ;
              sh:property [ sh:path ex:q ; sh:maxCount [ shnex:count [ shnex:filterShape ex:T ; shnex:nodes [ shnex:pathValues ex:q ] ] ] ] .
            ex:T sh:node ex:S .
        """
        val report = validate(provider, "ex:a ex:p ex:b ; ex:q ex:b , ex:c . ex:c ex:bad 1 .", shapes)
        assertTrue(report.violations.none { it.isUndefinedRecursion }, report.violations.toString())
        assertEquals(listOf(ex("a") to ConstraintType.MAX_COUNT), report.violations.map { it.focusNode to it.constraint.constraintType })
        // ex:b fails ex:S too: no value is a T, so sh:maxCount 0 fails, and so does the sh:node.
        val worse = validate(provider, "ex:a ex:p ex:b ; ex:q ex:b , ex:c . ex:b ex:bad 1 . ex:c ex:bad 1 .", shapes)
        assertTrue(worse.violations.none { it.isUndefinedRecursion }, worse.violations.toString())
        assertEquals(
            setOf(ex("a") to ConstraintType.MAX_COUNT, ex("a") to ConstraintType.NODE),
            worse.violations.map { it.focusNode to it.constraint.constraintType }.toSet(),
            worse.violations.toString(),
        )
    }

    // --- recognition (D4) -----------------------------------------------------------------------------------------------

    @Test
    fun `a blank node that is no node expression at a dynamic parameter is a compile error`() {
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", prop("sh:minCount [ ex:foo 1 ]")) }
        assertTrue(error.cause is ShapeCompileException, error.toString())
        assertTrue("neither a constant nor a node expression" in error.message.orEmpty(), error.message)
    }

    // --- reifier deactivation (D7) --------------------------------------------------------------------------------------

    @Test
    fun `a reifier deactivates a dynamic parameter triple`() {
        fun shapes(annotation: String) = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:PS .
            ex:PS sh:path ex:p ; sh:minCount _:e $annotation .
            _:e shnex:if true ; shnex:then 5 ; shnex:else 5 .
        """
        val data = "ex:a ex:p 1 ; ex:skip true . ex:b ex:p 1 ."
        val active = validate("jena", data, shapes(""))
        assertEquals(listOf(ex("a"), ex("b")), active.violations.map { it.focusNode }.sortedBy { it.toString() })
        assertTrue(active.violations.all { it.constraint.constraintType == ConstraintType.MIN_COUNT })
        assertTrue(validate("jena", data, shapes("{| sh:deactivated true |}")).violations.isEmpty())
        val perFocus = validate("jena", data, shapes("{| sh:deactivated [ shnex:pathValues ex:skip ] |}"))
        assertEquals(listOf(ex("b")), perFocus.violations.map { it.focusNode })
        assertEquals(ConstraintType.MIN_COUNT, perFocus.violations.single().constraint.constraintType)
    }

    // --- constants -------------------------------------------------------------------------------------------------------

    @Test
    fun `constant scalar parameters behave as before`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b , ex:c ; sh:property ex:P . ex:P sh:path ex:p ; sh:minCount 1 ; sh:maxInclusive 10 ."
        val compiled = ShapesCompiler.compile(g(shapes).getTriples(), ValidationConfig.default())
        assertEquals(
            listOf(PropertyConstraint.MinCount(1), PropertyConstraint.MaxInclusive(int(10))),
            compiled.referencedPropertyShapes.getValue(ex("P")).constraints,
        )
        val report = validate("jena", "ex:b ex:p 11 . ex:c ex:p 5 .", shapes)
        assertEquals(
            setOf(ex("a") to ConstraintType.MIN_COUNT, ex("b") to ConstraintType.MAX_INCLUSIVE),
            report.violations.map { it.focusNode to it.constraint.constraintType }.toSet(),
        )
        assertEquals(2, report.violations.size)
    }

    // --- every single-node property parameter ----------------------------------------------------------------------------

    /** [parameter] is [strict] for a focus node with ex:strict, else [lax]; both focus nodes have the ex:v [values]. */
    private class Case(val parameter: String, val strict: String, val lax: String, val values: String, val type: ConstraintType)

    private val singleNodeCases = listOf(
        Case("sh:minCount", "2", "0", "1", ConstraintType.MIN_COUNT),
        Case("sh:maxCount", "1", "5", "1 , 2", ConstraintType.MAX_COUNT),
        Case("sh:minLength", "5", "1", "\"abc\"", ConstraintType.MIN_LENGTH),
        Case("sh:maxLength", "2", "10", "\"abc\"", ConstraintType.MAX_LENGTH),
        Case("sh:minInclusive", "10", "0", "5", ConstraintType.MIN_INCLUSIVE),
        Case("sh:maxInclusive", "1", "10", "5", ConstraintType.MAX_INCLUSIVE),
        Case("sh:minExclusive", "5", "0", "5", ConstraintType.MIN_EXCLUSIVE),
        Case("sh:maxExclusive", "5", "10", "5", ConstraintType.MAX_EXCLUSIVE),
        Case("sh:uniqueLang", "true", "false", "\"a\"@en , \"b\"@en", ConstraintType.UNIQUE_LANG),
        Case("sh:singleLine", "true", "false", "\"a\\nb\"", ConstraintType.SINGLE_LINE),
        Case("sh:minListLength", "3", "0", "( 1 2 )", ConstraintType.MIN_LIST_LENGTH),
        Case("sh:maxListLength", "1", "5", "( 1 2 )", ConstraintType.MAX_LIST_LENGTH),
        Case("sh:uniqueMembers", "true", "false", "( 1 1 )", ConstraintType.UNIQUE_MEMBERS),
    )

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `every single-node property parameter accepts an expression`(provider: String) {
        for (case in singleNodeCases) {
            val expression = "[ shnex:if [ sparql:bound ( [ shnex:pathValues ex:strict ] ) ] ; shnex:then ${case.strict} ; shnex:else ${case.lax} ]"
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:s , ex:l ; sh:property [ sh:path ex:v ; ${case.parameter} $expression ] ."
            val data = "ex:s ex:strict true ; ex:v ${case.values} . ex:l ex:v ${case.values} ."
            val report = validate(provider, data, shapes)
            assertTrue(report.violations.isNotEmpty(), "${case.parameter}: no violation")
            assertEquals(setOf(ex("s")), report.violations.map { it.focusNode }.toSet(), "${case.parameter}: ${report.violations}")
            assertTrue(report.violations.all { it.constraint.constraintType == case.type }, "${case.parameter}: ${report.violations}")
        }
    }
}
