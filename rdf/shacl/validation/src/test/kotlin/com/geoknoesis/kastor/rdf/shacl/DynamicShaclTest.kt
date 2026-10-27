package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.native.DynamicComponent
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.providers.DynamicInstanceCache
import com.geoknoesis.kastor.rdf.shacl.providers.dynamicInstanceCacheFor
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
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

    /**
     * [data] validated against [shapes] on a run budget of **work**, not time: the clock advances by one each time the
     * engine consults the budget (every 64th tick), so the run fails with "timed out" past [consultations], on any host.
     * Gives the report and the consultations used.
     */
    private fun validateWorkBounded(consultations: Long, data: String, shapes: String): Pair<ValidationReport, Long> {
        var now = 0L
        val validator = NativeShaclValidator(ValidationConfig.default().copy(timeout = java.time.Duration.ofNanos(consultations))) { repository("jena") }
            .also { it.budgetClock = { now++ } }
        return validator.validate(g(data), g(shapes)) to now
    }

    /** The shapes graph validated against itself, as the W3C cases do. */
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
    fun `a failed computed sh closed or qualified count inside sh not or sh node stays a failure`(provider: String) {
        // Checked only for conformance, a failed evaluation must not count as "does not conform" (Review Focus #3).
        val inners = listOf(
            "[ sh:closed [ shnex:pathValues ex:none ] ]" to "sh:closed",
            "[ sh:closed true ; sh:ignoredProperties [ shnex:pathValues ex:v ] ]" to "sh:ignoredProperties",
            "[ sh:property [ sh:path ex:v ; sh:qualifiedValueShape [ sh:datatype xsd:integer ] ; sh:qualifiedMinCount [ shnex:pathValues ex:none ] ] ]" to
                "sh:qualifiedMinCount",
            "[ sh:property [ sh:path ex:v ; sh:qualifiedValueShape [ sh:datatype xsd:integer ] ; sh:qualifiedMaxCount [ shnex:pathValues ex:v ] ] ]" to
                "sh:qualifiedMaxCount",
        )
        for ((inner, parameter) in inners) {
            for (wrapper in listOf("sh:not", "sh:node")) {
                val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; $wrapper $inner ."
                val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:v 1 , 2 .", shapes) }, "$wrapper $inner")
                val message = error.message.orEmpty()
                assertTrue("Dynamic SHACL" in message && parameter in message, "$wrapper $inner: $message")
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `ill-formed constants that are now node expressions`(provider: String) {
        // CHANGELOG "Changed": these were compile errors (or, for the bounds, a constant blank-node bound).
        // A list at a single-node parameter is a list expression: sh:minCount ( 1 ) means 1.
        val minCount = validate(provider, "ex:a ex:q 1 .", prop("sh:minCount ( 1 )")).violations.single()
        assertEquals(ConstraintType.MIN_COUNT, minCount.constraint.constraintType)
        assertTrue(validate(provider, "ex:a ex:v 1 .", prop("sh:minCount ( 1 )")).violations.isEmpty())
        // [] is the empty expression: no nodes, a run-time failure, at the IRI-or-list and the single-node parameters.
        for (body in listOf(
            "sh:datatype []", "sh:nodeKind []", "sh:rootClass []", "sh:minCount []", "sh:maxLength []", "sh:pattern []",
            "sh:minInclusive []", "sh:maxExclusive []",
        )) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:v 1 .", prop(body)) }, body)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && "no nodes" in message, "$body: $message")
        }
        // At a pure-list parameter, no nodes is the empty list: sh:languageIn [] rejects every value.
        val languageIn = validate(provider, "ex:a ex:v \"x\"@en , \"y\" .", prop("sh:languageIn []"))
        assertEquals(2, languageIn.violations.size, languageIn.violations.toString())
        assertTrue(languageIn.violations.all { it.constraint.constraintType == ConstraintType.LANGUAGE_IN }, languageIn.violations.toString())
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
    fun `a dynamic parameter in a recursive shape reports only decided violations, with no undefined results`(provider: String) {
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

    // --- list-taking parameters (D2 rows 2 and 3, D6) --------------------------------------------------------------------

    /** §8.2 Examples 32 and 33 verbatim. */
    private val addresses = """
        ex:ArizonaAddress1
            a ex:Address ;
            ex:street "123 John Muir Ave" ;
            ex:country ex:USA ;
            ex:state "AZ" ;
        .
        ex:QueenslandAddress1
            a ex:Address ;
            ex:street "123 Bob Katter Cl" ;
            ex:country ex:Australia ;
            ex:state "QLD" ;
        .
        ex:Australia
            a            ex:Country ;
            ex:stateCode "ACT",
                         "NSW",
                         "NT",
                         "QLD",
                         "SA",
                         "TAS",
                         "VIC",
                         "WA" ;
        .
        ex:USA
            a            ex:Country ;
            ex:stateCode "AL",
                         "AK",
                         "AZ",
                         "AR" ; # ...
        .
    """

    /** §8.2 Example 34 verbatim. */
    private val addressShapes = """
        ex:Address
            a sh:ShapeClass ;
            sh:property ex:Address-state ;
        .
        ex:Address-state
            a sh:PropertyShape ;
            sh:path ex:state ;
            sh:in [
                shnex:pathValues ( ex:country ex:stateCode )
            ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `spec example 34 computes the allowed states from the country`(provider: String) {
        val data = addresses + "\nex:Bad a ex:Address ; ex:country ex:USA ; ex:state \"QLD\" ."
        val v = validate(provider, data, addressShapes).violations.single()
        assertEquals(ex("Bad"), v.focusNode)
        assertEquals("QLD", (v.value as Literal).lexical)
        assertEquals(ConstraintType.IN, v.constraint.constraintType)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an sh in expression with no output rejects every value`(provider: String) {
        val data = addresses + "\nex:Lost a ex:Address ; ex:country ex:Nowhere ; ex:state \"AZ\" ."
        val v = validate(provider, data, addressShapes).violations.single()
        assertEquals(ex("Lost") to ConstraintType.IN, v.focusNode to v.constraint.constraintType)
        assertEquals("AZ", (v.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh class takes the outputs as alternatives`(provider: String) {
        val data = "ex:a ex:allowedType ex:A , ex:B ; ex:v ex:x , ex:y . ex:x a ex:B . ex:y a ex:C ."
        val v = validate(provider, data, prop("sh:class [ shnex:pathValues ex:allowedType ]")).violations.single()
        assertEquals(Triple(ex("a"), ex("y"), ConstraintType.CLASS), Triple(v.focusNode, v.value, v.constraint.constraintType))
        // One output is a single class.
        assertTrue(validate(provider, "ex:a ex:allowedType ex:B ; ex:v ex:x . ex:x a ex:B .", prop("sh:class [ shnex:pathValues ex:allowedType ]")).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh class, sh datatype, sh nodeKind and sh rootClass need at least one output`(provider: String) {
        for (parameter in listOf("sh:class", "sh:datatype", "sh:nodeKind", "sh:rootClass")) {
            val error = assertThrows(ShaclValidationException::class.java, {
                validate(provider, "ex:a ex:v 1 .", prop("$parameter [ shnex:pathValues ex:none ]"))
            }, parameter)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && parameter in message && "no nodes" in message, message)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic list members must be well-typed`(provider: String) {
        val cases = listOf(
            "sh:class" to "\"lit\"",
            "sh:rootClass" to "\"lit\"",
            "sh:datatype" to "[]",
            "sh:nodeKind" to "ex:Foo",
            "sh:nodeKind" to "sh:IRI , sh:IRIOrLiteral",
            "sh:languageIn" to "ex:en",
            // A literal, but not a string: only the D6 rule rejects it (a constant list would take its lexical form).
            "sh:languageIn" to "1",
        )
        for ((parameter, bad) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, {
                validate(provider, "ex:a ex:v 1 ; ex:p $bad .", prop("$parameter [ shnex:pathValues ex:p ]"))
            }, "$parameter $bad")
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && parameter in message, message)
        }
    }

    /** [parameter] computed from the ex:p values [allowed]: the ex:v values [values] give exactly one violation, of [type]. */
    private class ListCase(val parameter: String, val allowed: String, val values: String, val type: ConstraintType)

    private val listCases = listOf(
        ListCase("sh:datatype", "xsd:integer , xsd:string", "1 , \"s\" , true", ConstraintType.DATATYPE),
        ListCase("sh:nodeKind", "sh:IRIOrLiteral", "1 , ex:x , []", ConstraintType.NODE_KIND),
        ListCase("sh:nodeKind", "sh:IRI , sh:BlankNode", "1 , ex:x , []", ConstraintType.NODE_KIND),
        ListCase("sh:rootClass", "ex:Top", "ex:Sub , ex:Other", ConstraintType.ROOT_CLASS),
        ListCase("sh:languageIn", "\"en\" , \"fr\"", "\"a\"@en , \"b\"@fr-CA , \"c\"@de", ConstraintType.LANGUAGE_IN),
        ListCase("sh:in", "1 , 2", "1 , 2 , 3", ConstraintType.IN),
    )

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `every list-taking parameter accepts an expression`(provider: String) {
        for (case in listCases) {
            val data = "ex:Sub rdfs:subClassOf ex:Top . ex:a ex:p ${case.allowed} ; ex:v ${case.values} ."
            val report = validate(provider, data, prop("${case.parameter} [ shnex:pathValues ex:p ]"))
            assertEquals(listOf(case.type), report.violations.map { it.constraint.constraintType }, "${case.parameter} ${case.allowed}: ${report.violations}")
        }
    }

    @Test
    fun `dynamic sh datatype keeps the severity annotated on its triple`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property ex:P .
            ex:P sh:path ex:v ; sh:datatype _:e {| sh:severity sh:Warning |} .
            _:e shnex:pathValues ex:dt .
        """
        val compiled = ShapesCompiler.compile(g(shapes).getTriples(), ValidationConfig.default())
        assertTrue(compiled.referencedPropertyShapes.getValue(ex("P")).constraints.single() is PropertyConstraint.Dynamic)
        val v = validate("jena", "ex:a ex:dt xsd:integer ; ex:v \"x\" .", shapes).violations.single()
        assertEquals(ConstraintType.DATATYPE, v.constraint.constraintType)
        assertEquals(ViolationSeverity.WARNING, v.severity)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `constant lists and blank-node sh hasValue keep their meaning when shapes are data`(provider: String) {
        val ttl = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:c ;
              sh:property ex:P , ex:Q , ex:R , ex:K .
            ex:P sh:path ex:p ; sh:in ( ex:a ex:b ) .
            ex:Q sh:path ex:q ; sh:hasValue _:x .
            ex:R sh:path ex:r ; sh:languageIn ( "en" ) .
            ex:K sh:path ex:k ; sh:class ( ex:A ex:B ) ; sh:nodeKind ( sh:IRI ) ; sh:datatype ( xsd:string ) .
            ex:a ex:p ex:b ; ex:q _:x ; ex:r "x"@en .
            ex:c ex:p ex:c ; ex:r "y"@fr ; ex:k ex:a .
        """
        val compiled = ShapesCompiler.compile(g(ttl).getTriples(), ValidationConfig.default())
        val constraints = listOf("P", "Q", "R", "K").flatMap { compiled.referencedPropertyShapes.getValue(ex(it)).constraints }
        assertTrue(constraints.none { it is PropertyConstraint.Dynamic }, constraints.toString())
        assertTrue(PropertyConstraint.In(listOf(ex("a"), ex("b"))) in constraints, constraints.toString())
        assertTrue(PropertyConstraint.ClassAnyOf(listOf(ex("A"), ex("B"))) in constraints, constraints.toString())
        val report = validateSelf(provider, ttl)
        assertEquals(
            setOf(
                ex("c") to ConstraintType.IN, ex("c") to ConstraintType.HAS_VALUE, ex("c") to ConstraintType.LANGUAGE_IN,
                ex("c") to ConstraintType.CLASS, ex("c") to ConstraintType.DATATYPE,
            ),
            report.violations.map { it.focusNode to it.constraint.constraintType }.toSet(),
            report.violations.toString(),
        )
        assertEquals(5, report.violations.size, report.violations.toString())
    }

    @Test
    fun `at a list-taking parameter a malformed list stays a compile error and an undeclared call is unsupported`() {
        // [ rdf:rest () ] has the shape of a call [ fn ( … ) ], but it is a (malformed) constant list (D4 rule 2).
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        for (config in listOf(ValidationConfig.default(), ignore)) {
            val malformed = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", prop("sh:in [ rdf:rest () ]"), config) }
            val chain = generateSequence<Throwable>(malformed) { it.cause }.toList()
            assertTrue(chain.any { it is ShapeCompileException } && chain.none { it is UnsupportedShaclFeatureException }, malformed.toString())
            // A blank node that is neither a list nor a node expression is the D4 compile error (ruling D6), never unsupported.
            for (body in listOf("sh:class [ ex:foo 1 ]", "sh:in [ ex:foo 1 ]")) {
                val error = assertThrows(ShaclValidationException::class.java, { validate("jena", "ex:a ex:v 1 .", prop(body), config) }, body)
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                assertTrue(causes.any { it is ShapeCompileException } && causes.none { it is UnsupportedShaclFeatureException }, "$body: $error")
                assertTrue(causes.any { "neither a constant nor a node expression" in it.message.orEmpty() }, "$body: $error")
            }
        }
        val call = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:v 1 .", prop("sh:class [ ex:undeclared ( ex:A ) ]")) }
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported(call))
        assertTrue("sh:class" in call.message.orEmpty(), call.message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an sh in expression larger than maxPathValueNodes fails`(provider: String) {
        val config = ValidationConfig(maxPathValueNodes = 3)
        val error = assertThrows(ShaclValidationException::class.java) {
            validate(provider, "ex:a ex:allowed 1 , 2 , 3 , 4 ; ex:v 1 .", prop("sh:in [ shnex:pathValues ex:allowed ]"), config)
        }
        val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("maxPathValueNodes" in messages, messages)
    }

    @Test
    fun `targetWhere narrowing ignores dynamic value-type constraints`() {
        // Every node is an IRI or a literal, so every candidate is a member of ex:W: ex:a and the literal both fail sh:in.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetWhere ex:W ; sh:in ( ex:none ) .
            ex:W a sh:NodeShape ; sh:nodeKind [ shnex:if true ; shnex:then sh:IRIOrLiteral ; shnex:else sh:IRIOrLiteral ] .
        """
        val focusNodes = validate("jena", "ex:a ex:p \"lit\" .", shapes).violations.map { it.focusNode }
        assertTrue(ex("a") in focusNodes, focusNodes.toString())
        assertTrue(focusNodes.any { it is Literal && it.lexical == "lit" }, focusNodes.toString())
    }

    // --- multi-parameter components: sh:pattern / sh:flags ---------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh pattern reads the pattern from the data`(provider: String) {
        val v = validate(provider, "ex:a ex:pat \"^[0-9]+$\" ; ex:v \"12\" , \"12a\" .", prop("sh:pattern [ shnex:pathValues ex:pat ]")).violations.single()
        assertEquals(ex("a") to ConstraintType.PATTERN, v.focusNode to v.constraint.constraintType)
        assertEquals("12a", (v.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh flags applies to a constant pattern`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:i , ex:n ; sh:property ex:P . ex:P sh:path ex:v ; sh:pattern \"^abc$\" ; sh:flags [ shnex:pathValues ex:flags ] ."
        val compiled = ShapesCompiler.compile(g(shapes).getTriples(), ValidationConfig.default())
        assertTrue(compiled.referencedPropertyShapes.getValue(ex("P")).constraints.single() is PropertyConstraint.Dynamic)
        val report = validate(provider, "ex:i ex:flags \"i\" ; ex:v \"ABC\" . ex:n ex:flags \"\" ; ex:v \"ABC\" .", shapes)
        assertEquals(listOf(ex("n") to ConstraintType.PATTERN), report.violations.map { it.focusNode to it.constraint.constraintType })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an invalid dynamic pattern fails`(provider: String) {
        val cases = listOf(
            "sh:pattern [ shnex:pathValues ex:p ]" to "\"(\"",
            "sh:pattern [ shnex:pathValues ex:p ]" to "ex:iri",
            "sh:pattern [ shnex:pathValues ex:p ]" to "1",
            "sh:pattern \"a\" ; sh:flags [ shnex:pathValues ex:p ]" to "\"z\"",
            "sh:pattern \"a\" ; sh:flags [ shnex:pathValues ex:p ]" to "1",
        )
        for ((body, bad) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:v \"a\" ; ex:p $bad .", prop(body)) }, "$body $bad")
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && ("sh:pattern" in message || "sh:flags" in message), message)
        }
    }

    @Test
    fun `the constant half of a dynamic pattern is checked without focus nodes`() {
        // No target: no focus node is ever reached, so only the compiler can reject the constant half.
        val cases = listOf(
            "sh:pattern ex:iri ; sh:flags [ shnex:pathValues ex:f ]" to "sh:pattern must be a string literal",
            "sh:pattern [ shnex:pathValues ex:p ] ; sh:flags \"z\"" to "unsupported flag character",
        )
        for ((body, message) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate("jena", "ex:a ex:v 1 .", "ex:S a sh:NodeShape ; sh:property [ sh:path ex:v ; $body ] .") }, body)
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            assertTrue(causes.any { it is ShapeCompileException && message in it.message.orEmpty() }, "$body: $error")
        }
        // Whether a constant pattern compiles depends on the computed flags: "(" is invalid, except under q, which
        // quotes it. It is compiled per focus node, with the resolved flags.
        fun quoted(targets: String) =
            "ex:S a sh:NodeShape ; $targets sh:property [ sh:path ex:v ; sh:pattern \"(\" ; sh:flags [ shnex:pathValues ex:f ] ] ."
        // Without focus nodes, the shapes graph is accepted.
        assertTrue(validate("jena", "ex:a ex:v 1 .", quoted("")).isValid)
        val report = validate("jena", "ex:a ex:f \"q\" ; ex:v \"x(y\" . ex:b ex:f \"q\" ; ex:v \"xy\" .", quoted("sh:targetNode ex:a , ex:b ;"))
        assertEquals(listOf(ex("b") to ConstraintType.PATTERN), report.violations.map { it.focusNode to it.constraint.constraintType })
        val unquoted = assertThrows(ShaclValidationException::class.java) {
            validate("jena", "ex:a ex:f \"\" ; ex:v \"x\" .", quoted("sh:targetNode ex:a ;"))
        }
        assertTrue("Dynamic SHACL" in unquoted.message.orEmpty() && "Invalid sh:pattern" in unquoted.message.orEmpty(), unquoted.message)
    }

    @Test
    fun `an ignored unsupported sh flags expression leaves out the patterns and says so`() {
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:v \"b\" .", prop("sh:pattern \"^a$\" ; sh:flags [ ex:undeclared ( \"i\" ) ]"), ignore)
        assertTrue(report.violations.isEmpty(), report.violations.toString())
        assertTrue(
            report.warnings.any { "sh:flags" in it.message && "the constraints that use it are left out" in it.message },
            report.warnings.toString(),
        )
    }

    @Test
    fun `a pattern read from the data is bounded by the pattern budget`() {
        // Patterns run on a linear-time engine, so a budget is used up by a long value: 100,000 characters are more
        // than the 200,000 steps of 1 ms (three reads per character, seven steps per read).
        val hostile = "a".repeat(100_000) + "!"
        val config = ValidationConfig(patternTimeout = java.time.Duration.ofMillis(1))
        val started = System.nanoTime()
        val report = validate("jena", "ex:a ex:pat \"^a+b$\" ; ex:v \"$hostile\" .", prop("sh:pattern [ shnex:pathValues ex:pat ]"), config)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val v = report.violations.single()
        assertTrue(v.isPatternTimeout, v.toString())
        assertEquals(ConstraintType.PATTERN, v.constraint.constraintType)
        assertTrue(elapsedMs < 30_000, "took $elapsedMs ms")
    }

    @Test
    fun `the dynamic instance cache stops growing at its cap`() {
        val cache = DynamicInstanceCache(max = 4)
        val dynamic = PropertyConstraint.Dynamic(DynamicComponent.MIN_COUNT, emptyMap())
        val first = (0 until 10).map { n -> cache.getOrPut(dynamic, listOf(listOf(int(n)))) { PropertyConstraint.MinCount(n) } }
        assertEquals(4, cache.size)
        // A cached key gives the identical instance, not a new one.
        assertSame(first[0], cache.getOrPut(dynamic, listOf(listOf(int(0)))) { PropertyConstraint.MinCount(0) })
        // An uncached key is built every time, and the cache stays at its cap.
        var builds = 0
        val a = cache.getOrPut(dynamic, listOf(listOf(int(9)))) { builds++; PropertyConstraint.MinCount(9) }
        val b = cache.getOrPut(dynamic, listOf(listOf(int(9)))) { builds++; PropertyConstraint.MinCount(9) }
        assertEquals(2, builds)
        assertNotSame(a, b)
        assertEquals(4, cache.size)
    }

    @Test
    fun `the dynamic instance cache stops storing once its member budget is spent`() {
        // Each entry holds its key and an instance built from it (an sh:in list, say): both count against the budget.
        val cache = DynamicInstanceCache(max = 4096, maxMembers = 20)
        val dynamic = PropertyConstraint.Dynamic(DynamicComponent.IN, emptyMap())
        fun list(n: Int) = (0 until n).map { int(it) }
        cache.getOrPut(dynamic, listOf(list(4))) { PropertyConstraint.In(list(4)) }
        cache.getOrPut(dynamic, listOf(list(5))) { PropertyConstraint.In(list(5)) }
        assertEquals(2, cache.size)
        assertEquals(18L, cache.members)
        // A list of two would need 4 more members, past the budget of 20: built, not stored.
        var builds = 0
        val a = cache.getOrPut(dynamic, listOf(list(2))) { builds++; PropertyConstraint.In(list(2)) }
        val b = cache.getOrPut(dynamic, listOf(list(2))) { builds++; PropertyConstraint.In(list(2)) }
        assertEquals(2, builds)
        assertNotSame(a, b)
        assertEquals(2, cache.size)
        assertEquals(18L, cache.members)
        // An entry that still fits (one member, 2 nodes) is stored, and stored entries are served unchanged.
        val one = cache.getOrPut(dynamic, listOf(list(1))) { PropertyConstraint.In(list(1)) }
        assertSame(one, cache.getOrPut(dynamic, listOf(list(1))) { PropertyConstraint.In(list(1)) })
        assertEquals(20L, cache.members)
    }

    @Test
    fun `a run's dynamic instance cache takes its member cap from maxPathValueNodes`() {
        assertEquals(37L, dynamicInstanceCacheFor(37).maxMembers)
        assertEquals(1_000_000L, dynamicInstanceCacheFor(ValidationConfig.default().maxPathValueNodes).maxMembers)
        val cache = dynamicInstanceCacheFor(6)
        val dynamic = PropertyConstraint.Dynamic(DynamicComponent.IN, emptyMap())
        val three = (0 until 3).map { int(it) }
        val four = (0 until 4).map { int(it) }
        cache.getOrPut(dynamic, listOf(three)) { PropertyConstraint.In(three) }
        cache.getOrPut(dynamic, listOf(four)) { PropertyConstraint.In(four) }
        assertEquals(1, cache.size)
        assertEquals(6L, cache.members)
    }

    // --- multi-parameter components: qualified value shapes ---------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic qualified counts`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property [ sh:path ex:v ;
              sh:qualifiedValueShape [ sh:datatype xsd:integer ] ; sh:qualifiedMinCount [ shnex:pathValues ex:minQ ] ] .
        """
        val report = validate(provider, "ex:a ex:minQ 2 ; ex:v 1 , \"x\" . ex:b ex:minQ 1 ; ex:v 1 , \"x\" .", shapes)
        assertEquals(listOf(ex("a") to ConstraintType.QUALIFIED_MIN_COUNT), report.violations.map { it.focusNode to it.constraint.constraintType })
        val max = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property [ sh:path ex:v ;
              sh:qualifiedValueShape [ sh:datatype xsd:integer ] ; sh:qualifiedMinCount 1 ; sh:qualifiedMaxCount [ shnex:pathValues ex:maxQ ] ] .
        """
        val maxReport = validate(provider, "ex:a ex:maxQ 1 ; ex:v 1 , 2 . ex:b ex:maxQ 2 ; ex:v 1 , 2 .", max)
        assertEquals(listOf(ex("a") to ConstraintType.QUALIFIED_MAX_COUNT), maxReport.violations.map { it.focusNode to it.constraint.constraintType })
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:maxQ -1 ; ex:v 1 . ex:b ex:maxQ 2 .", max) }
        assertTrue("Dynamic SHACL" in error.message.orEmpty() && "sh:qualifiedMaxCount" in error.message.orEmpty(), error.message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh qualifiedValueShapesDisjoint excludes the sibling shapes only when true`(provider: String) {
        // 1 conforms to the sibling shape ex:Pos too: disjoint, it does not count for ex:Int.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:P1 , ex:P2 .
            ex:P1 sh:path ex:v ; sh:qualifiedValueShape ex:Int ; sh:qualifiedMinCount 1 ;
              sh:qualifiedValueShapesDisjoint [ shnex:pathValues ex:disjoint ] .
            ex:P2 sh:path ex:v ; sh:qualifiedValueShape ex:Pos ; sh:qualifiedMinCount 1 .
            ex:Int sh:datatype xsd:integer .
            ex:Pos sh:minInclusive 0 .
        """
        val report = validate(provider, "ex:a ex:disjoint true ; ex:v 1 . ex:b ex:disjoint false ; ex:v 1 .", shapes)
        assertEquals(listOf(ex("a") to ConstraintType.QUALIFIED_MIN_COUNT), report.violations.map { it.focusNode to it.constraint.constraintType })
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:disjoint 1 ; ex:v 1 . ex:b ex:disjoint false .", shapes) }
        assertTrue("Dynamic SHACL" in error.message.orEmpty() && "sh:qualifiedValueShapesDisjoint" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a reifier that deactivates a dynamic qualifiedMinCount leaves the qualifiedMaxCount`() {
        // The two counts have different reifiers, so they stay two constraints; ex:minQ is absent, which would fail.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:v ; sh:qualifiedValueShape [ sh:datatype xsd:integer ] ;
              sh:qualifiedMinCount [ shnex:pathValues ex:minQ ] {| sh:deactivated true |} ;
              sh:qualifiedMaxCount [ shnex:pathValues ex:maxQ ] ] .
        """
        val report = validate("jena", "ex:a ex:maxQ 1 ; ex:v 1 , 2 .", shapes)
        assertEquals(listOf(ex("a") to ConstraintType.QUALIFIED_MAX_COUNT), report.violations.map { it.focusNode to it.constraint.constraintType })
    }

    @Test
    fun `dynamic qualifiedMaxCount in a recursive shape stays non-monotone`() {
        // ex:a conforms to ex:S iff at most 0 of its ex:p values, i.e. ex:a itself, conform to ex:S: a cycle through a
        // negative dependency, undefined (R16), exactly as with the constant sh:qualifiedMaxCount 0. The polarity is the
        // instantiated constraint's: a maximum makes the read negative.
        fun shapes(max: String) = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ;
              sh:qualifiedValueShape ex:S ; sh:qualifiedMaxCount $max ] .
        """
        val data = "ex:a ex:p ex:a ; ex:max 0 ."
        val dynamic = validate("jena", data, shapes("[ shnex:pathValues ex:max ]"))
        val constant = validate("jena", data, shapes("0"))
        assertTrue(dynamic.violations.isNotEmpty() && dynamic.violations.all { it.isUndefinedRecursion }, dynamic.violations.toString())
        assertEquals(
            constant.violations.map { Triple(it.focusNode, it.value, it.isUndefinedRecursion) },
            dynamic.violations.map { Triple(it.focusNode, it.value, it.isUndefinedRecursion) },
        )
        // In strict mode, an undefined answer fails the run.
        val error = assertThrows(ShaclValidationException::class.java) {
            validate("jena", data, shapes("[ shnex:pathValues ex:max ]"), ValidationConfig(strictMode = true))
        }
        assertTrue("undefined" in error.message.orEmpty(), error.toString())
    }

    // --- multi-parameter components: sh:reificationRequired ---------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh reificationRequired with and without sh reifierShape`(provider: String) {
        val data = """
            ex:t ex:req true ; ex:p 1 .
            ex:f ex:req false ; ex:p 1 .
            ex:r ex:req true ; ex:p 2 {| ex:by ex:x |} .
            ex:s ex:req true ; ex:skip true ; ex:p 3 .
        """
        fun shapes(reifierShape: String, targets: String = "ex:t , ex:f , ex:r , ex:s") = """
            ex:S a sh:NodeShape ; sh:targetNode $targets ; sh:property ex:P .
            ex:P sh:path ex:p ; $reifierShape sh:reificationRequired _:e {| sh:deactivated [ shnex:pathValues ex:skip ] |} .
            _:e shnex:pathValues ex:req .
            ex:RS sh:property [ sh:path ex:by ; sh:minCount 1 ] .
        """
        for ((reifierShape, type) in listOf("" to ConstraintType.REIFICATION_REQUIRED, "sh:reifierShape ex:RS ;" to ConstraintType.REIFIER_SHAPE)) {
            val report = validate(provider, data, shapes(reifierShape))
            assertEquals(listOf(ex("t") to type), report.violations.map { it.focusNode to it.constraint.constraintType }, "$reifierShape ${report.violations}")
        }
        // No output, or one that is no xsd:boolean (constants would read "yes" as false), fails.
        for (bad in listOf("", "ex:req \"yes\" ;")) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:t $bad ex:p 1 .", shapes("", targets = "ex:t")) }, bad)
            assertTrue("Dynamic SHACL" in error.message.orEmpty() && "sh:reificationRequired" in error.message.orEmpty(), error.message)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `computed sh closed and sh reificationRequired are evaluated for literal focus nodes`(provider: String) {
        // A literal has no properties to close and no triples to reify, but a failed evaluation still fails the run
        // (§3.4), as for sh:uniqueValuesFor and the Core constraints.
        val failing = listOf(
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:closed [ shnex:pathValues ex:mode ] ." to "sh:closed",
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:closed [ shnex:var \"focusNode\" ] ." to "sh:closed",
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:closed true ; sh:ignoredProperties [ shnex:var \"focusNode\" ] ." to
                "sh:ignoredProperties",
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:property [ sh:path ex:p ; sh:reificationRequired [ shnex:pathValues ex:req ] ] ." to
                "sh:reificationRequired",
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:property [ sh:path ex:p ; sh:reifierShape ex:RS ; sh:reificationRequired [ shnex:var \"focusNode\" ] ] ." to
                "sh:reificationRequired",
        )
        for ((shapes, parameter) in failing) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", shapes) }, shapes)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && parameter in message, "$shapes: $message")
        }
        // Evaluations that succeed leave nothing to check on a literal.
        val passing = listOf(
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:closed ( true ) ; sh:ignoredProperties [ shnex:pathValues ex:mode ] .",
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" ; sh:property [ sh:path ex:p ; sh:reificationRequired ( true ) ] .",
        )
        for (shapes in passing) assertTrue(validate(provider, "ex:a ex:p 1 .", shapes).violations.isEmpty(), shapes)
    }

    @Test
    fun `dynamic sh reificationRequired on a complex path is reported`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ( ex:a ex:b ) ; sh:reificationRequired [ shnex:pathValues ex:none ] ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:a ex:x . ex:x ex:b 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.REIFIER_CONSTRAINT_ON_COMPLEX_PATH), unsupported(error))
        // Ignored, it constrains nothing: its expression, which has no output, is not evaluated.
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:a ex:x . ex:x ex:b 1 .", shapes, ignore)
        assertTrue(report.warnings.any { "sh:reificationRequired" in it.message }, report.warnings.toString())
        assertTrue(report.violations.isEmpty(), report.violations.toString())
    }

    // --- node-shape parameters: sh:closed, sh:ignoredProperties, sh:uniqueValuesFor --------------------------------------

    /** A node shape on [targets], with the property ex:p, closed by [closed]; ex:strict and ex:mode are ignored. */
    private fun closedShape(closed: String, extra: String = "", targets: String = "ex:a , ex:b") =
        "ex:S a sh:NodeShape ; sh:targetNode $targets ; sh:property [ sh:path ex:p ] ; sh:closed $closed ; " +
            "sh:ignoredProperties ( ex:strict ex:mode ) $extra ."

    /** The focus node and the disallowed predicate of each sh:closed result of [report]. */
    private fun closedResults(report: ValidationReport): List<Pair<RdfTerm?, String?>> {
        assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.CLOSED }, report.violations.toString())
        return report.violations.map { it.focusNode to it.constraint.path }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh closed`(provider: String) {
        val strict = closedShape("[ shnex:if [ sparql:bound ( [ shnex:pathValues ex:strict ] ) ] ; shnex:then true ; shnex:else false ]")
        val report = validate(provider, "ex:a ex:strict true ; ex:p 1 ; ex:q 2 . ex:b ex:p 1 ; ex:q 2 .", strict)
        assertEquals(listOf(ex("a") to ex("q").value), closedResults(report))
        // sh:ByTypes from the data: the type ex:T of ex:a is the target class of ex:TS, which declares ex:q; rdf:type is
        // allowed. ex:b is not closed (false), ex:c is closed (true).
        val byTypes = closedShape("[ shnex:pathValues ex:mode ]", targets = "ex:a , ex:b , ex:c") +
            "\nex:T a rdfs:Class . ex:TS a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:q ] ."
        val data = "ex:a a ex:T ; ex:mode sh:ByTypes ; ex:q 1 ; ex:r 2 . ex:b ex:mode false ; ex:r 2 . ex:c ex:mode true ; ex:p 1 ; ex:q 1 ."
        assertEquals(setOf(ex("a") to ex("r").value, ex("c") to ex("q").value), closedResults(validate(provider, data, byTypes)).toSet())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh closed rejects other values`(provider: String) {
        val shapes = closedShape("[ shnex:pathValues ex:mode ]", targets = "ex:a")
        for (bad in listOf("\"maybe\"", "ex:iri", "\"true\"", "1")) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:mode $bad .", shapes) }, bad)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && "sh:closed" in message && "invalid value" in message, "$bad: $message")
        }
        for ((data, count) in listOf("ex:a ex:p 1 ." to "no nodes", "ex:a ex:mode true , false ." to "2 nodes")) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, data, shapes) }, data)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && "sh:closed" in message && count in message, "$data: $message")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh ignoredProperties`(provider: String) {
        // The members of the constant lists and the outputs of the expression are ignored; no output is the empty list.
        val shapes = closedShape("true", "; sh:ignoredProperties ( ex:extraAllowed ) , [ shnex:pathValues ex:extraAllowed ]")
        val data = "ex:a ex:p 1 ; ex:extraAllowed ex:q ; ex:q 1 ; ex:r 1 . ex:b ex:p 1 ; ex:q 1 ."
        assertEquals(setOf(ex("a") to ex("r").value, ex("b") to ex("q").value), closedResults(validate(provider, data, shapes)).toSet())
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:extraAllowed \"q\" .", shapes) }
        val message = error.message.orEmpty()
        assertTrue("Dynamic SHACL" in message && "sh:ignoredProperties" in message && "invalid value" in message, message)
        // With sh:closed computed, both are evaluated per focus node.
        val both = closedShape("[ shnex:pathValues ex:mode ]", "; sh:ignoredProperties [ shnex:pathValues ex:extraAllowed ]")
        val bothData = "ex:a ex:mode true ; ex:extraAllowed ex:q ; ex:q 1 ; ex:r 1 . ex:b ex:mode false ; ex:r 1 ."
        assertEquals(
            listOf(ex("a") to ex("extraAllowed").value, ex("a") to ex("r").value),
            closedResults(validate(provider, bothData, both)).sortedBy { it.second },
        )
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `dynamic sh uniqueValuesFor`(provider: String) {
        // Each focus node is compared, on its own properties, with every other target. Under [k1], ex:a shares "x" with
        // ex:b; under [k1, k2], ex:b shares ("x", "3") with no one; under [k2], ex:e shares "2" with ex:c only. ex:c is
        // deactivated per focus node by the reifier, but stays a target the others are compared with. ex:d, deactivated
        // too, has no properties: deactivation is checked before they are resolved, so that does not fail.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor _:e {| sh:deactivated [ shnex:pathValues ex:skip ] |} .
            _:e shnex:pathValues ex:keys .
        """
        val data = """
            ex:a a ex:T ; ex:keys ex:k1 ; ex:k1 "x" ; ex:k2 "1" .
            ex:b a ex:T ; ex:keys ex:k1 , ex:k2 ; ex:k1 "x" ; ex:k2 "3" .
            ex:c a ex:T ; ex:keys ex:k2 ; ex:k1 "y" ; ex:k2 "2" ; ex:skip true .
            ex:e a ex:T ; ex:keys ex:k2 ; ex:k2 "2" .
            ex:d a ex:T ; ex:skip true .
        """
        val report = validate(provider, data, shapes)
        assertEquals(setOf(ex("a"), ex("e")), report.violations.map { it.focusNode }.toSet(), report.violations.toString())
        assertEquals(2, report.violations.size, report.violations.toString())
        assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.UNIQUE_VALUES_FOR }, report.violations.toString())
        // No output, or a member that is no IRI, fails.
        for (bad in listOf("", "ex:keys \"k1\" ;")) {
            val error = assertThrows(ShaclValidationException::class.java, {
                validate(provider, "ex:a a ex:T ; $bad ex:k1 1 . ex:b a ex:T ; ex:keys ex:k1 ; ex:k1 1 .", shapes)
            }, bad)
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && "sh:uniqueValuesFor" in message, "$bad: $message")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a dynamic sh uniqueValuesFor is evaluated whatever the number of targets`(provider: String) {
        // One target has no other to share a key with, but its parameter is still evaluated: a failure is a failure (§3.4).
        val computed = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:uniqueValuesFor [ shnex:pathValues ex:keys ] ."
        assertTrue(validate(provider, "ex:a ex:keys ex:k ; ex:k 1 .", computed).violations.isEmpty())
        val cases = listOf(
            computed to "ex:a ex:k 1 .",
            computed to "ex:a ex:keys \"k\" .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:uniqueValuesFor [] ." to "ex:a ex:k 1 .",
            // A literal focus node has no key, but its parameter is evaluated too.
            "ex:S a sh:NodeShape ; sh:targetNode \"lit\" , ex:a , ex:b ; sh:uniqueValuesFor [ shnex:pathValues ex:keys ] ." to
                "ex:a ex:keys ex:k . ex:b ex:keys ex:k .",
        )
        for ((shapes, data) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, data, shapes) }, "$shapes / $data")
            val message = error.message.orEmpty()
            assertTrue("Dynamic SHACL" in message && "sh:uniqueValuesFor" in message, "$shapes / $data: $message")
        }
    }

    @Test
    fun `a dynamic sh uniqueValuesFor compares every property, not only the rarest`() {
        // ex:c shares the rare k2 "1" with ex:a, ex:b and ex:d but not k1; ex:b, keyed on k1 alone, shares "x" with ex:a
        // and ex:d whatever their k2; ex:e has no value for its only property, so no key.
        val data = """
            ex:a a ex:T ; ex:keys ex:k1 , ex:k2 ; ex:k1 "x" ; ex:k2 "1" .
            ex:b a ex:T ; ex:keys ex:k1 ; ex:k1 "x" ; ex:k2 "1" .
            ex:c a ex:T ; ex:keys ex:k1 , ex:k2 ; ex:k1 "y" ; ex:k2 "1" .
            ex:d a ex:T ; ex:keys ex:k1 , ex:k2 ; ex:k1 "x" ; ex:k2 "1" .
            ex:e a ex:T ; ex:keys ex:k3 .
            ex:f a ex:T ; ex:keys ex:k1 , ex:k2 ; ex:k1 "x" .
        """
        val shapes = "ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor [ shnex:pathValues ex:keys ] ."
        val report = validate("jena", data, shapes)
        assertEquals(
            mapOf(ex("a") to 2, ex("b") to 3, ex("d") to 2),
            report.violations.associate { it.focusNode to Regex("shared with ([0-9]+)").find(it.message)!!.groupValues[1].toInt() },
            report.violations.toString(),
        )
    }

    @Test
    fun `a dynamic sh uniqueValuesFor on a value shared by many targets stays linear`() {
        // 12000 targets share ex:c 1 and differ on ex:d, and every one is keyed on ex:c (with ex:e, which none has): the
        // rarest value set of each is the one all share, so scanning it per focus node would compare 144 million keys.
        val n = 12_000
        val data = buildString {
            for (i in 0 until n) appendLine("ex:t$i a ex:T ; ex:keys ex:c , ex:e ; ex:c 1 ; ex:d $i .")
        }
        val shapes = "ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor [ shnex:pathValues ex:keys ] ."
        // About 87,000 budget consultations; rescanning the shared value set per focus node needs about 2.25 million.
        val (report, _) = validateWorkBounded(260_000L, data, shapes)
        assertEquals(ValidationConfig.default().maxViolations, report.violations.size)
        assertTrue(report.violations.all { "shared with ${n - 1} other" in it.message }, report.violations.take(3).toString())
    }

    @Test
    fun `a dynamic sh uniqueValuesFor with a distinct property list per target stays linear`() {
        // 12000 targets, each keyed on a property of its own: one pass over the targets per distinct list would compare
        // 144 million keys; the work budget stops such a run. ex:x and ex:y share ex:t0's key on ex:k0, and
        // nothing else is shared.
        val n = 12_000
        val data = buildString {
            for (i in 0 until n) appendLine("ex:t$i a ex:T ; ex:keys ex:k$i ; ex:k$i 1 .")
            appendLine("ex:x a ex:T ; ex:keys ex:k0 ; ex:k0 1 ; ex:k1 2 .")
            appendLine("ex:y a ex:T ; ex:keys ex:k0 ; ex:k0 1 .")
        }
        val shapes = "ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor [ shnex:pathValues ex:keys ] ."
        // About 62,000 budget consultations; a pass over the targets per distinct list needs about 2.25 million.
        val (report, _) = validateWorkBounded(190_000L, data, shapes)
        assertEquals(setOf(ex("t0"), ex("x"), ex("y")), report.violations.map { it.focusNode }.toSet(), report.violations.toString())
        assertTrue(report.violations.all { "shared with 2 other" in it.message }, report.violations.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `validateClosedShapes=false also skips dynamic sh closed`(provider: String) {
        // "maybe" would fail the run, so sh:closed is not even evaluated.
        val shapes = closedShape("[ shnex:pathValues ex:mode ]", targets = "ex:a")
        val report = validate(provider, "ex:a ex:mode \"maybe\" ; ex:q 1 .", shapes, ValidationConfig(validateClosedShapes = false))
        assertTrue(report.violations.isEmpty(), report.violations.toString())
    }

    @Test
    fun `a reifier deactivates a dynamic sh closed before it is evaluated`() {
        // The sh:closed value "maybe" of ex:b would fail; a reifier of either triple deactivates the check first.
        val data = "ex:a ex:mode true ; ex:q 1 . ex:b ex:mode \"maybe\" ; ex:skip true ; ex:q 1 ."
        val reifier = "{| sh:deactivated [ shnex:pathValues ex:skip ] |}"
        for ((closed, ignored) in listOf("_:m $reifier" to "", "_:m" to reifier)) {
            val shapes = """
                ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:closed $closed ; sh:ignoredProperties ( ex:mode ex:skip ) $ignored .
                _:m shnex:pathValues ex:mode .
            """
            assertEquals(listOf(ex("a") to ex("q").value), closedResults(validate("jena", data, shapes)), shapes)
        }
    }

    @Test
    fun `the constant parameters of a dynamic sh closed are checked without focus nodes`() {
        // No target: only the compiler can reject them.
        val cases = listOf(
            "sh:closed [ shnex:pathValues ex:m ] ; sh:ignoredProperties [ rdf:first ex:p ]" to "Ill-formed RDF list",
            "sh:closed [ shnex:pathValues ex:m ] , true" to "sh:closed",
            "sh:closed true ; sh:ignoredProperties [ shnex:pathValues ex:i ] , [ shnex:pathValues ex:j ]" to "sh:ignoredProperties",
            "sh:uniqueValuesFor [ shnex:pathValues ex:k ] , ex:k" to "sh:uniqueValuesFor",
        )
        for ((body, message) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate("jena", "ex:a ex:v 1 .", "ex:S a sh:NodeShape ; $body .") }, body)
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            assertTrue(causes.any { it is ShapeCompileException && message in it.message.orEmpty() }, "$body: $error")
        }
    }

    @Test
    fun `node-shape parameter expressions follow the detection split`() {
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val data = "ex:a ex:p 1 ; ex:q 1 . ex:b ex:p 1 ; ex:q 1 ."
        val calls = listOf(
            "sh:closed [ ex:undeclared ( true ) ]",
            "sh:closed true ; sh:ignoredProperties [ ex:undeclared ( ex:q ) ]",
            "sh:uniqueValuesFor [ ex:undeclared ( ex:p ) ]",
        )
        for (body in calls) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; $body ."
            val error = assertThrows(ShaclValidationException::class.java, { validate("jena", data, shapes) }, body)
            assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported(error), body)
            // Ignored, the constraint that uses it is left out, and the warning says so.
            val report = validate("jena", data, shapes, ignore)
            assertTrue(report.violations.isEmpty(), "$body: ${report.violations}")
            assertTrue(report.warnings.any { "the constraints that use it are left out" in it.message }, "$body: ${report.warnings}")
        }
        for (body in listOf("sh:closed [ ex:foo 1 ]", "sh:closed true ; sh:ignoredProperties [ ex:foo 1 ]", "sh:uniqueValuesFor [ ex:foo 1 ]")) {
            for (config in listOf(ValidationConfig.default(), ignore)) {
                val error = assertThrows(ShaclValidationException::class.java, {
                    validate("jena", data, "ex:S a sh:NodeShape ; sh:targetNode ex:a ; $body .", config)
                }, body)
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                assertTrue(causes.any { it is ShapeCompileException && "neither a constant nor a node expression" in it.message.orEmpty() }, "$body: $error")
                assertTrue(causes.none { it is UnsupportedShaclFeatureException }, "$body: $error")
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `node-shape parameter expressions have their shapes compiled`(provider: String) {
        // ex:Strict and ex:Keyed are reachable only from the expressions.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property [ sh:path ex:p ] ; sh:ignoredProperties ( ex:strict ) ;
              sh:closed [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Strict ) ] .
            ex:Strict sh:property [ sh:path ex:strict ; sh:minCount 1 ] .
            ex:U a sh:NodeShape ; sh:targetNode ex:a , ex:b ;
              sh:uniqueValuesFor [ shnex:if [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Keyed ) ] ; shnex:then ex:p ; shnex:else ex:q ] .
            ex:Keyed sh:property [ sh:path ex:strict ; sh:minCount 1 ] .
        """
        val report = validate(provider, "ex:a ex:strict 1 ; ex:p 1 ; ex:q 1 . ex:b ex:p 1 ; ex:q 2 .", shapes)
        assertEquals(
            setOf(ex("a") to ConstraintType.CLOSED, ex("a") to ConstraintType.UNIQUE_VALUES_FOR),
            report.violations.map { it.focusNode to it.constraint.constraintType }.toSet(),
            report.violations.toString(),
        )
        assertEquals(2, report.violations.size, report.violations.toString())
    }
}
