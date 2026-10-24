package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SHACL 1.2 Core §3.1.6: a reifier of a triple that has a shape as subject and a parameter as predicate deactivates
 * the constraints that use the triple, when `evalExpr(expr, data graph, focus node, {})` of its `sh:deactivated`
 * value produces `true` as its only output node: the constant `true`, or a node expression evaluated per focus node.
 */
class ReifierDeactivationTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private val d = "$"

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config) { repository(provider) }.validate(g(data), g(shapes))

    private fun unsupported(error: Throwable): Set<UnsupportedShaclFeature> =
        generateSequence(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first().features

    // --- every constraint kind, constant and per focus node ------------------------------------------------------------

    /**
     * One constraint, whose parameter triple carries the annotation `{D}`. The data `{F}` (and `{V}`, a value node of
     * its own) is written for the focus nodes ex:a and ex:b. Active, each focus node has [results] results;
     * deactivated, [remaining].
     */
    private class Case(val name: String, val shapes: String, val data: String, val results: Int = 1, val remaining: Int = 0)

    private val helpers = """
        ex:N a sh:NodeShape ; sh:class ex:C .
        ex:Any a sh:NodeShape .
        ex:Q sh:path ex:r ; sh:minCount 1 .
        ex:BadComponent a sh:ConstraintComponent ; sh:parameter [ sh:path ex:bad ] ;
          sh:nodeValidator [ a sh:SPARQLSelectValidator ;
            sh:select '''SELECT ${d}this WHERE { ${d}this <http://example.com/ns#p> ${d}bad }''' ] .
    """

    private fun prop(body: String) = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:P . ex:P sh:path ex:p ; $body .\n$helpers"
    private fun node(body: String) = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; $body .\n$helpers"

    private val cases = listOf(
        // Property shapes.
        Case("minCount", prop("sh:minCount 2 {D}"), "{F} ex:p 1 ."),
        Case("maxCount", prop("sh:maxCount 0 {D}"), "{F} ex:p 1 ."),
        Case("datatype", prop("sh:datatype xsd:string {D}"), "{F} ex:p 1 ."),
        Case("class", prop("sh:class ex:C {D}"), "{F} ex:p {V} ."),
        Case("class list", prop("sh:class ( ex:C ex:D ) {D}"), "{F} ex:p {V} ."),
        Case("nodeKind", prop("sh:nodeKind sh:IRI {D}"), "{F} ex:p 1 ."),
        Case("pattern", prop("sh:pattern \"^z\" {D}"), "{F} ex:p \"a\" ."),
        Case("flags (deactivates the pattern)", prop("sh:pattern \"^z\" ; sh:flags \"m\" {D}"), "{F} ex:p \"a\" ."),
        Case("minLength", prop("sh:minLength 5 {D}"), "{F} ex:p \"a\" ."),
        Case("maxLength", prop("sh:maxLength 0 {D}"), "{F} ex:p \"a\" ."),
        Case("in", prop("sh:in ( 2 ) {D}"), "{F} ex:p 1 ."),
        Case("hasValue", prop("sh:hasValue 2 {D}"), "{F} ex:p 1 ."),
        Case("languageIn", prop("sh:languageIn ( \"en\" ) {D}"), "{F} ex:p \"x\"@de ."),
        Case("uniqueLang", prop("sh:uniqueLang true {D}"), "{F} ex:p \"x\"@en , \"y\"@en ."),
        Case("equals", prop("sh:equals ex:q {D}"), "{F} ex:p 1 ."),
        Case("disjoint", prop("sh:disjoint ex:q {D}"), "{F} ex:p 1 ; ex:q 1 ."),
        Case("lessThan", prop("sh:lessThan ex:q {D}"), "{F} ex:p 2 ; ex:q 1 ."),
        Case("lessThanOrEquals", prop("sh:lessThanOrEquals ex:q {D}"), "{F} ex:p 2 ; ex:q 1 ."),
        Case("subsetOf", prop("sh:subsetOf ex:q {D}"), "{F} ex:p 1 ."),
        Case("minInclusive", prop("sh:minInclusive 5 {D}"), "{F} ex:p 1 ."),
        Case("maxInclusive", prop("sh:maxInclusive 0 {D}"), "{F} ex:p 1 ."),
        Case("minExclusive", prop("sh:minExclusive 5 {D}"), "{F} ex:p 1 ."),
        Case("maxExclusive", prop("sh:maxExclusive 0 {D}"), "{F} ex:p 1 ."),
        Case("singleLine", prop("sh:singleLine true {D}"), "{F} ex:p \"a\\nb\" ."),
        Case("rootClass", prop("sh:rootClass ex:R {D}"), "{F} ex:p ex:K ."),
        Case("node", prop("sh:node ex:N {D}"), "{F} ex:p {V} ."),
        Case("nodeByExpression (IRI)", prop("sh:nodeByExpression ex:N {D}"), "{F} ex:p {V} ."),
        Case("nodeByExpression (computed)", prop("sh:nodeByExpression ( ex:N ) {D}"), "{F} ex:p {V} ."),
        Case("someValue", prop("sh:someValue ex:N {D}"), "{F} ex:p {V} ."),
        Case("qualifiedValueShape", prop("sh:qualifiedValueShape ex:N {D} ; sh:qualifiedMinCount 1"), "{F} ex:p {V} ."),
        Case("qualifiedMinCount", prop("sh:qualifiedValueShape ex:N ; sh:qualifiedMinCount 1 {D}"), "{F} ex:p {V} ."),
        Case("qualifiedValueShapesDisjoint", prop("sh:qualifiedValueShape ex:N ; sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint false {D}"), "{F} ex:p {V} ."),
        // sh:qualifiedMinCount and sh:qualifiedMaxCount are two components: deactivating one leaves the other.
        Case("qualifiedMinCount next to qualifiedMaxCount", prop("sh:qualifiedValueShape ex:Any ; sh:qualifiedMinCount 5 {D} ; sh:qualifiedMaxCount 0"), "{F} ex:p {V} .", results = 2, remaining = 1),
        Case("qualifiedMaxCount next to qualifiedMinCount", prop("sh:qualifiedValueShape ex:Any ; sh:qualifiedMinCount 5 ; sh:qualifiedMaxCount 0 {D}"), "{F} ex:p {V} .", results = 2, remaining = 1),
        Case("expression", prop("sh:expression [ sparql:less-than ( [ shnex:var \"value\" ] 0 ) ] {D}"), "{F} ex:p 1 ."),
        Case("sparql", prop("sh:sparql [ sh:select '''SELECT ${d}this WHERE { ${d}this <http://example.com/ns#p> ?o }''' ] {D}"), "{F} ex:p 1 ."),
        Case("not", prop("sh:not ex:Any {D}"), "{F} ex:p {V} ."),
        Case("and", prop("sh:and ( ex:N ) {D}"), "{F} ex:p {V} ."),
        Case("or", prop("sh:or ( ex:N ) {D}"), "{F} ex:p {V} ."),
        Case("xone", prop("sh:xone ( ex:N ) {D}"), "{F} ex:p {V} ."),
        Case("nested property", prop("sh:property ex:Q {D}"), "{F} ex:p {V} ."),
        Case("minListLength", prop("sh:minListLength 5 {D}"), "{F} ex:p ( 1 2 ) ."),
        Case("maxListLength", prop("sh:maxListLength 0 {D}"), "{F} ex:p ( 1 2 ) ."),
        Case("memberShape", prop("sh:memberShape ex:N {D}"), "{F} ex:p ( {V} ) ."),
        Case("uniqueMembers", prop("sh:uniqueMembers true {D}"), "{F} ex:p ( 1 1 ) ."),
        Case("reifierShape (missing reifier)", prop("sh:reifierShape ex:Any {D} ; sh:reificationRequired true"), "{F} ex:p 1 ."),
        Case("reificationRequired", prop("sh:reifierShape ex:Any ; sh:reificationRequired true {D}"), "{F} ex:p 1 ."),
        Case("reificationRequired alone", prop("sh:reificationRequired true {D}"), "{F} ex:p 1 ."),
        Case("reifierShape (failing reifier)", prop("sh:reifierShape ex:N {D}"), "{F} ex:p 1 {| ex:x 1 |} ."),
        // Node shapes.
        Case("node shape: node", node("sh:node ex:N {D}"), "{F} ex:p 1 ."),
        Case("node shape: nodeByExpression", node("sh:nodeByExpression ex:N {D}"), "{F} ex:p 1 ."),
        Case("node shape: class", node("sh:class ex:C {D}"), "{F} ex:p 1 ."),
        Case("node shape: datatype", node("sh:datatype xsd:string {D}"), "{F} ex:p 1 ."),
        Case("node shape: nodeKind", node("sh:nodeKind sh:Literal {D}"), "{F} ex:p 1 ."),
        Case("node shape: hasValue", node("sh:hasValue ex:z {D}"), "{F} ex:p 1 ."),
        Case("node shape: expression", node("sh:expression [ shnex:exists [ shnex:pathValues ex:missing ] ] {D}"), "{F} ex:p 1 ."),
        Case("node shape: sparql", node("sh:sparql [ sh:select '''SELECT ${d}this WHERE { ${d}this <http://example.com/ns#p> ?o }''' ] {D}"), "{F} ex:p 1 ."),
        Case("node shape: SPARQL-based constraint component", node("ex:bad 1 {D}"), "{F} ex:p 1 ."),
        Case("node shape: and", node("sh:and ( ex:N ) {D}"), "{F} ex:p 1 ."),
        Case("node shape: or", node("sh:or ( ex:N ) {D}"), "{F} ex:p 1 ."),
        Case("node shape: xone", node("sh:xone ( ex:N ) {D}"), "{F} ex:p 1 ."),
        Case("node shape: not", node("sh:not ex:Any {D}"), "{F} ex:p 1 ."),
        Case("node shape: property", node("sh:property ex:Q {D}"), "{F} ex:p 1 ."),
        Case("node shape: closed", node("sh:closed true {D} ; sh:ignoredProperties ( ex:skip )"), "{F} ex:p 1 ."),
        Case("node shape: ignoredProperties (deactivates sh:closed)", node("sh:closed true ; sh:ignoredProperties ( ex:skip ) {D}"), "{F} ex:p 1 ."),
        Case("node shape: uniqueValuesFor", node("sh:uniqueValuesFor ex:k {D}"), "{F} ex:k 1 ."),
    )

    private val perFocus = "{| sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ] |}"

    /** Results per focus node: the local name of the focus node up to `_` (so ex:a_v counts for ex:a). */
    private fun resultsPerFocus(report: ValidationReport): Map<String, Int> =
        report.violations.groupingBy { (it.focusNode as Iri).value.substringAfter('#').substringBefore('_') }.eachCount()

    private fun expected(a: Int, b: Int) = buildMap { if (a > 0) put("a", a); if (b > 0) put("b", b) }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a reifier deactivates the constraint of every kind, constantly or for the focus nodes whose expression is true`(provider: String) {
        val mismatches = ArrayList<String>()
        for (case in cases) {
            // ex:a has ex:skip in every variant, so that only the deactivation tells ex:a and ex:b apart.
            val data = "ex:a ex:skip true .\n" + listOf("a", "b").joinToString("\n") { f -> case.data.replace("{F}", "ex:$f").replace("{V}", "ex:${f}_v") }
            val variants = listOf(
                Triple("active", "", expected(case.results, case.results)),
                Triple("false", "{| sh:deactivated false |}", expected(case.results, case.results)),
                Triple("true", "{| sh:deactivated true |}", expected(case.remaining, case.remaining)),
                Triple("per focus", perFocus, expected(case.remaining, case.results)),
            )
            for ((variant, annotation, want) in variants) {
                val got = try {
                    resultsPerFocus(validate(provider, data, case.shapes.replace("{D}", annotation)))
                } catch (e: Exception) {
                    mapOf("error: ${e.message?.take(300)}" to 0)
                }
                if (got != want) mismatches.add("${case.name} [$variant]: expected $want, got $got")
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SPARQL expression at a reifier's sh deactivated`(provider: String) {
        val annotation = "{| sh:deactivated [ sh:sparqlExpr '''EXISTS { ${d}this <http://example.com/ns#skip> ?x }''' ] |}"
        val data = "ex:a ex:skip 1 ; ex:p 1 . ex:b ex:p 1 ."
        assertEquals(listOf(ex("b")), validate(provider, data, prop("sh:minCount 2 $annotation")).violations.map { it.focusNode })
        assertEquals(listOf(ex("b")), validate(provider, data, node("sh:class ex:C $annotation")).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `only exactly true deactivates a constraint`(provider: String) {
        val shapes = node("sh:class ex:C {| sh:deactivated [ shnex:pathValues ex:flag ] |}")
        assertEquals(listOf(ex("b")), validate(provider, "ex:a ex:flag true . ex:b ex:p 1 .", shapes).violations.map { it.focusNode })
        for (flag in listOf("false", "\"1\"^^xsd:boolean", "true , \"true\"")) {
            val data = "ex:a ex:flag $flag . ex:b ex:p 1 ."
            assertEquals(setOf(ex("a"), ex("b")), validate(provider, data, shapes).violations.map { it.focusNode }.toSet(), flag)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a failing expression at a reifier's sh deactivated fails validation`(provider: String) {
        val failing = "{| sh:deactivated [ sparql:abs ( ( 1 2 ) ) ] |}"
        for (shapes in listOf(
            prop("sh:minCount 2 $failing"),
            node("sh:class ex:C $failing"),
            node("sh:property ex:Q $failing"),
            node("sh:node ex:N $failing"),
            node("sh:or ( ex:N ) $failing"),
            node("sh:closed true $failing"),
            node("sh:uniqueValuesFor ex:k $failing"),
            prop("sh:reificationRequired true $failing"),
        )) {
            assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 ; ex:k 1 . ex:b ex:k 1 .", shapes) }, shapes)
        }
    }

    // --- recursion: a shape read by a reifier's expression is a negative dependency -------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a shape read by a reifier's sh deactivated is a negative dependency`(provider: String) {
        val notS = "{| sh:deactivated [ sparql:logical-not ( [ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:S ) ] ) ] |}"
        val byY = "{| sh:deactivated [ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:Y ) ] |}"
        val y = "ex:Y a sh:NodeShape ; sh:node ex:S ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] ."
        // Each constraint is active iff x conforms to ex:S, which holds iff the constraint is not: a paradox. Read
        // through ex:Y, which fails sh:minCount on its own, the constraint is active: 1 result.
        val forms = listOf(
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P . ex:P sh:path ex:p ; sh:maxCount 0 {D} .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:class ex:C {D} .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P {D} . ex:P sh:path ex:p ; sh:maxCount 0 .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:not ex:Any {D} . ex:Any a sh:NodeShape .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:closed true {D} .",
        )
        for (form in forms) {
            assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:x ex:p 1 .", form.replace("{D}", notS)) }, form)
            assertEquals(1, validate(provider, "ex:x ex:p 1 .", form.replace("{D}", byY) + "\n" + y).violations.size, form)
        }
    }

    // --- well-formedness and unsupported forms -----------------------------------------------------------------------

    @Test
    fun `a reifier sh deactivated that is no node expression is unsupported`() {
        val shapes = node("sh:class ex:C {| sh:deactivated [ ex:undeclared 1 ] |}")
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported(error))
        // Ignored, the constraint stays active.
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:p 1 . ex:b ex:p 1 .", shapes, ignore)
        assertTrue(report.warnings.any { "Unsupported SHACL feature ignored" in it.message && "sh:deactivated" in it.message }, report.warnings.toString())
        assertEquals(2, report.violations.size)
    }

    @Test
    fun `a shape only a reifier's expression reads is inspected for unsupported features`() {
        // ex:Z (no shape type, no target) is reachable only through the reifier's expression; its sh:target is unsupported.
        val shapes = node("sh:class ex:C {| sh:deactivated [ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:Z ) ] |}") +
            "\nex:Z sh:target [ a ex:CustomTarget ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p 1 .", shapes) }
        assertEquals(setOf(UnsupportedShaclFeature.CUSTOM_TARGET), unsupported(error))
    }

    @Test
    fun `a parameter triple has at most one reifier with sh deactivated, with at most one value`() {
        val twoReifiers = node("sh:class ex:C") + """
            ex:r1 rdf:reifies <<( ex:S sh:class ex:C )>> ; sh:deactivated false .
            ex:r2 rdf:reifies <<( ex:S sh:class ex:C )>> ; sh:deactivated true .
        """
        val twoValues = node("sh:class ex:C {| sh:deactivated true , false |}")
        for ((shapes, message) in listOf(twoReifiers to "at most one reifier", twoValues to "at most one value")) {
            val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p 1 .", shapes) }
            val compile = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ShapeCompileException>().firstOrNull()
            assertTrue(compile != null && message in compile.message.orEmpty(), "$message: $error")
        }
    }

    // --- optimisations that read constraints -------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `sh targetWhere candidates are not narrowed by a constraint a reifier deactivates`(provider: String) {
        // ex:n is no ex:C and no literal; the membership constraint of ex:W is deactivated for it (per focus node) or
        // for every node (constant), so ex:n conforms to ex:W and is a target of ex:T.
        val target = "ex:T a sh:NodeShape ; sh:targetWhere ex:W ; sh:property [ sh:path ex:skip ; sh:maxCount 0 ] ."
        val data = "ex:n ex:skip 1 . ex:c a ex:C ; ex:q 2 ."
        for (membership in listOf("sh:class ex:C", "sh:datatype xsd:integer", "sh:nodeKind sh:Literal")) {
            for (annotation in listOf(perFocus, "{| sh:deactivated true |}")) {
                val shapes = "$target ex:W a sh:NodeShape ; $membership $annotation ."
                assertEquals(listOf(ex("n")), validate(provider, data, shapes).violations.map { it.focusNode }, "$membership $annotation")
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `sh uniqueValuesFor deactivated for a focus node by a reifier still counts it as a target`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b , ex:c ; sh:uniqueValuesFor ( ex:id ) $perFocus ."
        val report = validate(provider, "ex:a ex:id 1 ; ex:skip 1 . ex:b ex:id 1 . ex:c ex:id 2 .", shapes)
        assertEquals(listOf(ex("b")), report.violations.map { it.focusNode })
    }

    // --- evaluation counts, Kleene logic, well-formedness of every triple ----------------------------------------------

    private fun onlyA(shapes: String) = shapes.replace("sh:targetNode ex:a , ex:b", "sh:targetNode ex:a")

    @Test
    fun `the reifier condition of sh reifierShape and sh reificationRequired is evaluated once per focus node`() {
        // ex:a has no ex:skip: each constraint is active and evaluates its reifier's expression exactly once.
        for ((shapes, data) in listOf(
            prop("sh:reifierShape ex:N $perFocus") to "ex:a ex:p 1 {| ex:x 1 |} .",
            prop("sh:reifierShape ex:N ; sh:reificationRequired true $perFocus") to "ex:a ex:p 1 .",
            prop("sh:reificationRequired true $perFocus") to "ex:a ex:p 1 .",
        )) {
            val validator = NativeShaclValidator(ValidationConfig.default()) { repository("jena") }
            val report = validator.validate(g(data), g(onlyA(shapes)))
            assertEquals(1, report.violations.size, "$shapes: ${report.violations}")
            assertEquals(1L, validator.deactivationEvaluations, shapes)
        }
    }

    @Test
    fun `the reifier condition of a property shape's logical constraint is evaluated once per focus node, not per value node`() {
        val validator = NativeShaclValidator(ValidationConfig.default()) { repository("jena") }
        val report = validator.validate(g("ex:a ex:p 1 , 2 , 3 ."), g(onlyA(prop("sh:not ex:Any $perFocus"))))
        assertEquals(3, report.violations.size, report.violations.toString())
        assertEquals(1L, validator.deactivationEvaluations)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a definite true at one reifier deactivates the constraint although another reifier's expression is undefined for now`(provider: String) {
        // The first reifier reads whether ex:x conforms to ex:S, a negative self-dependency the solver answers
        // provisionally; the second deactivates the constraint for ex:x. True or undefined is true (Kleene), so the
        // constraint is deactivated, ex:x conforms to ex:S, and the first expression is false.
        val notS = "{| sh:deactivated [ sparql:logical-not ( [ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:S ) ] ) ] |}"
        for ((shapes, data) in listOf(
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P . ex:P sh:path ex:p ; sh:pattern \"^z\" $notS ; sh:flags \"m\" $perFocus ." to
                "ex:x ex:p \"a\" ; ex:skip true .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:closed true $notS ; sh:ignoredProperties ( ex:skip ) $perFocus ." to
                "ex:x ex:p 1 ; ex:skip true .",
        )) {
            val report = validate(provider, data, shapes)
            assertTrue(report.isValid, "$shapes: ${report.violations}")
        }
    }

    @Test
    fun `the at most one reifier rule is checked on every parameter triple of a constraint`() {
        // The constant true on sh:pattern deactivates the constraint; its sh:flags triple is still checked.
        val twoReifiers = prop("sh:pattern \"^z\" {| sh:deactivated true |} ; sh:flags \"m\"") + """
            ex:r1 rdf:reifies <<( ex:P sh:flags "m" )>> ; sh:deactivated false .
            ex:r2 rdf:reifies <<( ex:P sh:flags "m" )>> ; sh:deactivated false .
        """
        val twoValues = prop("sh:pattern \"^z\" {| sh:deactivated true |} ; sh:flags \"m\" {| sh:deactivated false , \"false\" |}")
        for ((shapes, message) in listOf(twoReifiers to "at most one reifier", twoValues to "at most one value")) {
            val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p \"a\" .", shapes) }
            val compile = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ShapeCompileException>().firstOrNull()
            assertTrue(compile != null && message in compile.message.orEmpty(), "$message: $error")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a reifier's sh deactivated on a triple that is no constraint parameter has no effect and is reported as a warning`(provider: String) {
        val data = "ex:a ex:p 1 ; ex:skip true . ex:b ex:p 1 ."
        for (shapes in listOf(
            "ex:S a sh:NodeShape ; sh:targetNode ex:a {| sh:deactivated true |} , ex:b ; sh:class ex:C .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property ex:P . ex:P sh:path ex:p $perFocus ; sh:maxCount 0 .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:message \"m\" {| sh:deactivated true |} ; sh:class ex:C .",
        )) {
            val report = validate(provider, data, shapes)
            assertEquals(setOf(ex("a"), ex("b")), report.violations.map { it.focusNode }.toSet(), shapes)
            assertTrue(report.warnings.any { "has no effect" in it.message && "sh:deactivated" in it.message }, "$shapes: ${report.warnings}")
            assertTrue(report.warnings.none { "Unsupported" in it.message }, "$shapes: ${report.warnings}")
            assertEquals(false, report.isValid)
        }
    }

    @Test
    fun `a shape only an ignored reifier expression reads does not fail the run`() {
        // ex:Z (no shape type, no target) is read only by the sh:deactivated of a reifier of a target triple, which has
        // no effect: its unsupported sh:target is not inspected. Neither is a value that is no node expression.
        for (expression in listOf("[ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:Z ) ]", "[ ex:undeclared 1 ]")) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a {| sh:deactivated $expression |} ; sh:class ex:C .\n" +
                "ex:Z sh:target [ a ex:CustomTarget ] ."
            val report = validate("jena", "ex:a ex:p 1 .", shapes)
            assertEquals(listOf(ex("a")), report.violations.map { it.focusNode }, expression)
        }
    }
}
