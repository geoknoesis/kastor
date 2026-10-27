package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.native.compileShaclPattern
import com.geoknoesis.kastor.rdf.shacl.providers.NativePatternEvaluator
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.PATTERN_DEGRADED_STEPS_PER_CHAR
import com.geoknoesis.kastor.rdf.shacl.providers.PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One `sh:pattern` evaluation has its own budget ([ValidationConfig.patternTimeout]). A value on which a
 * pattern uses it up is **reported** — a result with the status `ksh:PatternTimeout` that blocks conformance — and
 * validation goes on with
 * the other values and focus nodes: one slow or hostile literal does not abort the run. Only the run-wide
 * [ValidationConfig.timeout] (and `strictMode`) aborts.
 *
 * The tests are deterministic. The budget is counted in steps of the regular expression engine (character reads,
 * each weighted by the size of the compiled pattern);
 * the wall clock is only a backstop, and the tests inject it ([NativeShaclValidator.patternClock]).
 */
class PatternBudgetTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun graph(turtle: String): RdfGraph = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")

    /**
     * Patterns run on a linear-time engine, so what uses up a budget is a long value: three million characters, read
     * about three times each at the weight of a seven-instruction program, are more than the ten million steps of a
     * 50 ms budget. (`(.*a){30}` on sixty `a`s, which backtracked
     * catastrophically on java.util.regex, is decided at once now.)
     */
    private val catastrophic = "^a+b$"
    private val hostile = "a".repeat(3_000_000) + "!"

    private fun propertyShape(constraint: String) = graph("ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; $constraint ] .")
    private val shapes = propertyShape("""sh:pattern "$catastrophic"""")

    /**
     * A clock that advances one millisecond each time it is read. A budget of n milliseconds is therefore used up
     * after n consultations (one every 1024 steps), on any machine and under any load.
     */
    private fun tickingClock(): () -> Long {
        var now = 0L
        return { now += 1_000_000L; now }
    }

    /** A clock that never advances: no pattern budget can be used up, however long the match takes. */
    private val frozenClock: () -> Long = { 0L }

    private fun validator(config: ValidationConfig = ValidationConfig(patternTimeout = Duration.ofMillis(50)), clock: () -> Long = tickingClock()) =
        NativeShaclValidator(config).also { it.patternClock = clock }

    // --- time budget -------------------------------------------------------------------------------------------------

    @Test
    fun `a value that uses up the pattern budget is reported and the other focus nodes are still validated`() {
        // The run-wide timeout stays at its 5 minute default: only the pattern budget can end the evaluation of ex:a.
        val data = graph(
            """
            ex:a a ex:T ; ex:p "$hostile" .
            ex:b a ex:T ; ex:p "b" .
            ex:c a ex:T ; ex:p "b" , "$hostile" .
            ex:z a ex:U .
            """,
        )
        val twoShapes = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ; sh:message "bad code" ] .
            ex:Z a sh:NodeShape ; sh:targetClass ex:U ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .
            """,
        )
        val report = validator().validate(data, twoShapes)
        assertFalse(report.isValid)

        val timeouts = report.violations.filter { it.isPatternTimeout }
        assertEquals(setOf<Any>(ex("a"), ex("c")), timeouts.map { it.focusNode }.toSet(), report.violations.toString())
        for (result in timeouts) {
            assertEquals(ConstraintType.PATTERN, result.constraint.constraintType)
            assertEquals(ViolationSeverity.VIOLATION, result.severity)
            assertEquals(hostile, (result.value as Literal).lexical)
            assertEquals(ValidationViolation.PATTERN_TIMEOUT_CODE, result.violationCode)
            assertEquals(KastorShaclVocabulary.PatternTimeout, result.resultStatus)
            assertTrue(result.isUndecided && !result.isUndefinedRecursion && !result.isPatternTooComplex)
            // The engine's explanation is never replaced by the shape's sh:message: it names the pattern and the budget.
            assertTrue(result.message.contains(catastrophic), result.message)
            assertTrue(result.message.contains("patternTimeout") && result.message.contains("PT0.05S"), result.message)
            assertTrue(result.message.contains("${hostile.length} characters"), result.message)
        }
        // Later values and focus nodes, and later shapes, are validated as usual.
        val ordinary = report.violations.filter { !it.isUndecided }
        assertEquals(
            setOf<Any>(ex("b") to ConstraintType.PATTERN, ex("c") to ConstraintType.PATTERN, ex("z") to ConstraintType.MIN_COUNT),
            ordinary.map { it.focusNode to it.constraint.constraintType }.toSet(),
            report.violations.toString(),
        )
        assertTrue(ordinary.filter { it.constraint.constraintType == ConstraintType.PATTERN }.all { it.message == "bad code" })
        assertEquals(5, report.violations.size, report.violations.toString())
    }

    @Test
    fun `the RDF report marks the result with ksh resultStatus ksh PatternTimeout`() {
        val report = validator().validate(graph("""ex:a a ex:T ; ex:p "$hostile" ."""), shapes)
        val rdf = report.toShaclValidationReportRdf().getTriples()
        val status = rdf.single { it.predicate == KastorShaclVocabulary.resultStatus }
        assertEquals(KastorShaclVocabulary.PatternTimeout, status.obj)
        fun of(predicate: Iri) = rdf.single { it.subject == status.subject && it.predicate == predicate }.obj
        assertEquals(Iri(SHACL.namespace + "PatternConstraintComponent"), of(SHACL.sourceConstraintComponent))
        assertEquals(SHACL.Violation, of(SHACL.resultSeverity))
        assertEquals(ex("a"), of(SHACL.focusNode))
        assertEquals(hostile, (of(SHACL.value) as Literal).lexical)
        assertTrue(rdf.any { it.predicate == SHACL.conforms && (it.obj as Literal).lexical == "false" })
    }

    @Test
    fun `strict mode still fails validation with an error naming the pattern`() {
        val strict = validator(ValidationConfig(patternTimeout = Duration.ofMillis(50), strictMode = true))
        val error = assertThrows(ShaclValidationException::class.java) { strict.validate(graph("""ex:a a ex:T ; ex:p "$hostile" ."""), shapes) }
        val message = error.message.orEmpty()
        assertTrue(message.contains(catastrophic), message)
        assertTrue(message.contains("patternTimeout"), message)
    }

    @Test
    fun `an undecided value is matched once per run`() {
        // Three focus nodes share the hostile value and one has an ordinary value: the budget is spent once.
        val data = graph(
            """
            ex:a a ex:T ; ex:p "$hostile" .
            ex:b a ex:T ; ex:p "$hostile" .
            ex:c a ex:T ; ex:p "$hostile" , "b" .
            """,
        )
        val validator = validator()
        val report = validator.validate(data, shapes)
        assertEquals(3, report.violations.count { it.isPatternTimeout }, report.violations.toString())
        assertEquals(2, validator.patternEvaluations, "one evaluation of the hostile value, one of \"b\"")
    }

    @Test
    fun `an undecided pattern is not read as a failure by sh not and does not hide a definite answer of sh or`() {
        // sh:not over a pattern that could not be evaluated must not conform silently (a hostile value would get
        // through a "must not match" constraint); it is reported as undecided, naming the cause.
        val negated = propertyShape("""sh:not [ sh:pattern "$catastrophic" ]""")
        val data = graph(
            """
            ex:a a ex:T ; ex:p "$hostile" .
            ex:b a ex:T ; ex:p "b" .
            """,
        )
        val report = validator().validate(data, negated)
        val result = report.violations.single()
        assertEquals(ex("a"), result.focusNode)
        assertEquals(ConstraintType.NOT, result.constraint.constraintType)
        assertTrue(result.isPatternTimeout, result.toString())
        assertTrue(result.message.contains(catastrophic) && result.message.contains("patternTimeout"), result.message)
        assertFalse(report.isValid)

        // Kleene logic: sh:or conforms as soon as one operand definitely conforms, whatever the undecided one is ...
        val either = propertyShape("""sh:or ( [ sh:pattern "$catastrophic" ] [ sh:minLength 1 ] )""")
        assertTrue(validator().validate(data, either).isValid)
        // ... and is undecided when no operand does.
        val neither = propertyShape("""sh:or ( [ sh:pattern "$catastrophic" ] [ sh:maxLength 1 ] )""")
        val undecided = validator().validate(data, neither).violations.single()
        assertEquals(ConstraintType.OR, undecided.constraint.constraintType)
        assertTrue(undecided.isPatternTimeout && undecided.focusNode == ex("a"), undecided.toString())

        // Through sh:node as well: the outer result carries the status of its cause.
        val nested = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:node ex:Code ] .
            ex:Code sh:pattern "$catastrophic" .
            """,
        )
        val viaNode = validator().validate(data, nested).violations
        assertEquals(setOf<Any>(ex("a") to true, ex("b") to false), viaNode.map { it.focusNode to it.isPatternTimeout }.toSet(), viaNode.toString())
        assertTrue(viaNode.all { it.constraint.constraintType == ConstraintType.NODE })
    }

    @Test
    fun `the run-wide timeout is the only thing that aborts`() {
        // The pattern budget is out of reach (an hour of steps, a frozen clock), so only the run timeout can end this
        // match. The run budget is work here, not time: its clock advances by one each time the match consults it.
        val validator = validator(ValidationConfig(timeout = Duration.ofNanos(1_000), patternTimeout = Duration.ofHours(1)), frozenClock)
        var consultations = 0L
        validator.budgetClock = { consultations++ }
        val error = assertThrows(ShaclValidationException::class.java) { validator.validate(graph("""ex:a a ex:T ; ex:p "$hostile" ."""), shapes) }
        // The run budget ended the match itself (phase "pattern matching"), not admission, preparation or a later phase.
        assertTrue(error.message.orEmpty().contains("pattern matching timed out"), error.message)
        assertEquals(1L, validator.patternEvaluations, "the run was aborted during the one evaluation of the hostile value")
    }

    @Test
    fun `ordinary evaluations are unaffected by the budget`() {
        // The backtracking pattern itself is fine on inputs it decides quickly, even with a clock that runs.
        assertFalse(validator().validate(graph("""ex:a a ex:T ; ex:p "b" ."""), shapes).isValid)
        assertFalse(validator().validate(graph("""ex:a a ex:T ; ex:p "aaa" ."""), shapes).isValid)
        // A linear pattern over a long value reads hundreds of thousands of characters: far below the step budget of
        // the default patternTimeout (two hundred million steps).
        val linear = propertyShape("""sh:pattern "^[ab]+$"""")
        val long = "ab".repeat(100_000)
        val validator = validator(ValidationConfig(), frozenClock)
        assertTrue(validator.validate(graph("""ex:a a ex:T ; ex:p "$long" ."""), linear).isValid)
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p "${long}c" ."""), linear)
        assertEquals(listOf(false), report.violations.map { it.isUndecided })
    }

    @Test
    fun `the pattern budget must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ZERO)) }
        assertThrows(IllegalArgumentException::class.java) { NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(-1))) }
    }

    // --- the budget is work, the clock is a backstop -----------------------------------------------------------------

    @Test
    fun `the budget is counted in regular expression engine steps, whatever the clock says`() {
        // The clock never advances, so nothing here depends on the speed of the machine or on pauses: the evaluation
        // of the hostile value is stopped after the number of steps that patternTimeout stands for.
        val config = ValidationConfig(patternTimeout = Duration.ofMillis(50), timeout = Duration.ofSeconds(30))
        val first = validator(config, frozenClock)
        val report = first.validate(graph("""ex:a a ex:T ; ex:p "$hostile" . ex:b a ex:T ; ex:p "aaa" ."""), shapes)
        assertEquals(listOf<Any>(ex("a") to true, ex("b") to false), report.violations.map { it.focusNode to it.isPatternTimeout })
        val message = report.violations.first().message
        assertTrue(message.contains("patternTimeout") && message.contains("PT0.05S") && message.contains("steps"), message)
        // The same run again gives the same report.
        val second = validator(config, frozenClock).validate(graph("""ex:a a ex:T ; ex:p "$hostile" . ex:b a ex:T ; ex:p "aaa" ."""), shapes)
        assertEquals(report.violations.map { it.message }, second.violations.map { it.message })
    }

    /**
     * RE2/J advances up to one thread per instruction of the compiled pattern for each character it reads, so a step
     * is a read weighted by the program size: `.{0,1000}x` (about 2,000 instructions) on 300,000 characters is
     * 900,000 reads (well under the 5 million reads of a 100 ms budget counted in reads) but almost two billion
     * steps. It took 6.9 s when reads were counted; now it stops at the budget.
     */
    @Test
    fun `a step is a character read weighted by the size of the compiled pattern`() {
        val heavy = propertyShape("""sh:pattern ".{0,1000}x"""")
        val config = ValidationConfig(patternTimeout = Duration.ofMillis(100))
        val validator = validator(config, frozenClock)
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p "${"a".repeat(300_000)}" ."""), heavy)
        assertEquals(listOf(true), report.violations.map { it.isPatternTimeout }, report.violations.map { it.message }.toString())
        val budget = NativePatternEvaluator.stepBudget(config)
        val weight = compileShaclPattern(".{0,1000}x", null).programSize.toLong()
        assertTrue(validator.patternSteps in budget..budget + 1024 * weight, "steps: ${validator.patternSteps}, budget $budget")
        // A small pattern on the same value reads it at a small weight, and is decided.
        val light = validator(config, frozenClock).validate(graph("""ex:a a ex:T ; ex:p "${"a".repeat(300_000)}" ."""), propertyShape("""sh:pattern "^a+$""""))
        assertTrue(light.isValid, light.violations.map { it.message }.toString())
    }

    /** After 16 exhausted budgets, a value of a pattern with a large program gets the reduced, linear budget. */
    @Test
    fun `the run-wide cap engages for a pattern with a large program`() {
        val heavy = propertyShape("""sh:pattern ".{0,1000}x"""")
        val config = ValidationConfig(patternTimeout = Duration.ofMillis(100))
        val values = (0 until 24).joinToString(" , ") { "\"${"a".repeat(20_000 + it)}\"" }
        val validator = validator(config, frozenClock)
        val report = validator.validate(graph("ex:a a ex:T ; ex:p $values ."), heavy)
        assertEquals(24, report.violations.count { it.isPatternTimeout }, report.violations.map { it.message }.toString())
        val budget = NativePatternEvaluator.stepBudget(config)
        val weight = compileShaclPattern(".{0,1000}x", null).programSize.toLong()
        val reduced = 20_024L * PATTERN_DEGRADED_STEPS_PER_CHAR
        val bound = PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS * budget + (24 - PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS) * reduced + 24 * 1024 * weight
        assertTrue(reduced < budget / 10, "the reduced budget must be well below the full one")
        assertTrue(validator.patternSteps <= bound, "steps: ${validator.patternSteps}, bound $bound")
    }

    @Test
    fun `a pause shorter than the wall-clock backstop does not time a pattern out`() {
        // A garbage collection pause, a suspended virtual machine: the clock jumps five seconds during an evaluation
        // whose budget is one second. The evaluation needs few steps, so it is decided.
        var consultations = 0
        val pausing: () -> Long = { if (consultations++ == 0) 0L else Duration.ofSeconds(5).toNanos() }
        val linear = propertyShape("""sh:pattern "^[ab]+$"""")
        val long = "ab".repeat(5_000)
        val validator = validator(ValidationConfig(patternTimeout = Duration.ofSeconds(1)), pausing)
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p "$long" . ex:b a ex:T ; ex:p "${long}c" ."""), linear)
        assertTrue(consultations > 2, "the clock is consulted during the match: $consultations")
        assertEquals(listOf<Any>(ex("b") to false), report.violations.map { it.focusNode to it.isUndecided }, report.violations.map { it.message }.toString())
    }

    @Test
    fun `the wall clock stops an evaluation at ten times the pattern timeout`() {
        // The step budget is far away (one hour), but the clock says that ten times the budget has passed.
        var consultations = 0
        val stuck: () -> Long = { if (consultations++ == 0) 0L else Duration.ofHours(10).toNanos() + 1 }
        val validator = validator(ValidationConfig(patternTimeout = Duration.ofHours(1)), stuck)
        val long = "ab".repeat(5_000)
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p "$long" ."""), propertyShape("""sh:pattern "^[ab]+$""""))
        assertEquals(listOf(true), report.violations.map { it.isPatternTimeout })
        // Just under ten times the budget, the evaluation goes on.
        var calls = 0
        val under: () -> Long = { if (calls++ == 0) 0L else Duration.ofHours(10).toNanos() - 1 }
        val decided = validator(ValidationConfig(patternTimeout = Duration.ofHours(1)), under)
        assertTrue(decided.validate(graph("""ex:a a ex:T ; ex:p "$long" ."""), propertyShape("""sh:pattern "^[ab]+$"""")).isValid)
    }

    // --- an answer undefined for two reasons ---------------------------------------------------------------------------

    @Test
    fun `an answer that is undefined by recursion and by a pattern reports the recursion and names the pattern`() {
        // ex:R is undefined for ex:x twice over: it negates itself, and its pattern cannot be evaluated on the value.
        fun shapes(first: String, second: String) = graph(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property [ sh:path ex:self ; sh:node ex:R ] .
            ex:R a sh:NodeShape ; $first ; $second .
            """,
        )
        val negation = "sh:not ex:R"
        val pattern = """sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ]"""
        val data = graph("""ex:x ex:self ex:x ; ex:p "$hostile" .""")
        for (variant in listOf(shapes(negation, pattern), shapes(pattern, negation))) {
            val result = validator().validate(data, variant).violations.single()
            assertEquals(ConstraintType.NODE, result.constraint.constraintType)
            // The status is deterministic: recursion wins, whatever the order of the constraints.
            assertTrue(result.isUndefinedRecursion && !result.isPatternTimeout, result.toString())
            assertEquals(KastorShaclVocabulary.UndefinedRecursion, result.resultStatus)
            // The other cause is not lost: the message names the pattern that could not be evaluated.
            assertTrue(result.message.contains(catastrophic) && result.message.contains("patternTimeout"), result.message)
        }
        // With only one of the causes, the status is that cause.
        val onlyPattern = graph(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property [ sh:path ex:self ; sh:node ex:R ] .
            ex:R a sh:NodeShape ; $pattern .
            """,
        )
        assertTrue(validator().validate(data, onlyPattern).violations.single().isPatternTimeout)
        val onlyRecursion = graph(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property [ sh:path ex:self ; sh:node ex:R ] .
            ex:R a sh:NodeShape ; $negation .
            """,
        )
        val recursion = validator().validate(data, onlyRecursion).violations.single()
        assertTrue(recursion.isUndefinedRecursion && !recursion.message.contains("patternTimeout"), recursion.toString())
    }

    @Test
    fun `sh and and sh or name the recursion when one operand is undefined by recursion and another by a pattern`() {
        val operands = listOf("ex:Undefined", "ex:Slow")
        for (order in listOf(operands, operands.reversed())) {
            val shapes = graph(
                """
                ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:and ( ${order.joinToString(" ")} ) .
                ex:T2 a sh:NodeShape ; sh:targetNode ex:x ; sh:or ( ${order.joinToString(" ")} ) .
                ex:Undefined a sh:NodeShape ; sh:not ex:Undefined .
                ex:Slow a sh:NodeShape ; sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ] .
                """,
            )
            val results = validator().validate(graph("""ex:x ex:p "$hostile" ."""), shapes).violations
            assertEquals(setOf(ConstraintType.AND, ConstraintType.OR), results.map { it.constraint.constraintType }.toSet(), results.toString())
            assertTrue(results.all { it.isUndefinedRecursion }, results.map { it.violationCode }.toString())
        }
    }

    // --- stack exhaustion --------------------------------------------------------------------------------------------

    /**
     * java.util.regex matched an alternation under a quantifier recursively, one group of stack frames per
     * repetition, so 300,000 repetitions needed tens of megabytes of stack. The linear-time engine does not recurse on
     * its input: the same match succeeds on a thread with a 512 KiB stack, and no value is ever undecided for want
     * of stack (`ksh:PatternTooComplex` is no longer reported).
     */
    @Test
    fun `a long value is decided on a small stack because the engine does not recurse`() {
        val deep = "ab".repeat(150_000)
        var result: Result<ValidationReport>? = null
        val thread = Thread(null, {
            result = runCatching {
                validator(ValidationConfig(), frozenClock).validate(
                    graph("""ex:a a ex:T ; ex:p "$deep" . ex:b a ex:T ; ex:p "abc" ."""),
                    propertyShape("""sh:pattern "^(a|b)*$""""),
                )
            }
        }, "pattern-budget-test", 512L * 1024)
        thread.start()
        thread.join()
        val report = result!!.getOrThrow()
        assertEquals(listOf<Any>(ex("b") to false), report.violations.map { it.focusNode to it.isUndecided })
    }
}
