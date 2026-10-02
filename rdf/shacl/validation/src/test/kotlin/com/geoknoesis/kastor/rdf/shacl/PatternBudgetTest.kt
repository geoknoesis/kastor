package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One `sh:pattern` evaluation has its own budget ([ValidationConfig.patternTimeout]). A value on which a
 * pattern uses it up, or on which the regular expression engine runs out of stack, is **reported** — a result with
 * the status `ksh:PatternTimeout` / `ksh:PatternTooComplex` that blocks conformance — and validation goes on with
 * the other values and focus nodes: one slow or hostile literal does not abort the run. Only the run-wide
 * [ValidationConfig.timeout] (and `strictMode`) aborts.
 *
 * The tests are deterministic. The budget is counted in steps of the regular expression engine (character reads);
 * the wall clock is only a backstop, and the tests inject it ([NativeShaclValidator.patternClock]). Stack exhaustion
 * does not depend on the stack of the test thread either: long values are matched on the run's pattern evaluation
 * thread, whose stack size the tests set ([NativeShaclValidator.patternWorkerStackBytes]).
 */
class PatternBudgetTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun graph(turtle: String): RdfGraph = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")

    /** `(.*a){30}` needs astronomically many backtracking steps to reject sixty `a`s followed by `!`. */
    private val catastrophic = "^(.*a){30}$"
    private val hostile = "a".repeat(60) + "!"

    private fun propertyShape(constraint: String) = graph("ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; $constraint ] .")
    private val shapes = propertyShape("""sh:pattern "$catastrophic"""")

    /**
     * A clock that advances one millisecond each time it is read. A budget of n milliseconds is therefore used up
     * after n consultations (about n * 1024 character reads), on any machine and under any load.
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
        val validator = validator(ValidationConfig(timeout = Duration.ofNanos(10_000), patternTimeout = Duration.ofHours(1)), frozenClock)
        var consultations = 0L
        validator.budgetClock = { consultations++ }
        val error = assertThrows(ShaclValidationException::class.java) { validator.validate(graph("""ex:a a ex:T ; ex:p "$hostile" ."""), shapes) }
        assertTrue(error.message.orEmpty().contains("timed out"), error.message)
    }

    @Test
    fun `ordinary evaluations are unaffected by the budget`() {
        // The backtracking pattern itself is fine on inputs it decides quickly, even with a clock that runs.
        assertFalse(validator().validate(graph("""ex:a a ex:T ; ex:p "b" ."""), shapes).isValid)
        assertFalse(validator().validate(graph("""ex:a a ex:T ; ex:p "aaa" ."""), shapes).isValid)
        // A linear pattern over a long value reads hundreds of thousands of characters: far below the step budget of
        // the default patternTimeout (fifty million steps).
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
     * `java.util.regex` matches an alternation under a quantifier recursively: one group of stack frames per
     * repetition, several hundred bytes each. The 300,000 repetitions of [deep] need tens of megabytes of stack.
     */
    private val deep = "ab".repeat(150_000)
    private val recursive = "^(a|b)*$"

    /** A stack on which [deep] cannot be matched, whatever the JVM and its settings (it needs a hundred times more). */
    private val smallStack = 512L * 1024

    /** A validator whose pattern evaluation thread has a [smallStack]: the overflow does not depend on `-Xss`. */
    private fun smallStackValidator(config: ValidationConfig = ValidationConfig()) =
        validator(config, frozenClock).also { it.patternWorkerStackBytes = smallStack }

    /** Runs [block] on a thread with [stackBytes] of stack, so that the test does not depend on the default stack size. */
    private fun <T> onThreadWithStack(stackBytes: Long, block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "pattern-budget-test", stackBytes)
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    @Test
    fun `a pattern that exhausts the stack is reported and the other focus nodes are still validated`() {
        val data = graph(
            """
            ex:a a ex:T ; ex:p "$deep" .
            ex:b a ex:T ; ex:p "abc" .
            ex:c a ex:T ; ex:p "abba" .
            """,
        )
        val validator = smallStackValidator()
        val report = validator.validate(data, propertyShape("""sh:pattern "$recursive""""))
        assertFalse(report.isValid)
        assertEquals(setOf<Any>(ex("a"), ex("b")), report.violations.map { it.focusNode }.toSet(), report.violations.map { it.message }.toString())
        val result = report.violations.single { it.focusNode == ex("a") }
        assertTrue(result.isPatternTooComplex && result.isUndecided && !result.isPatternTimeout, result.violationCode)
        assertEquals(ConstraintType.PATTERN, result.constraint.constraintType)
        assertEquals(KastorShaclVocabulary.PatternTooComplex, result.resultStatus)
        assertTrue(result.message.contains(recursive), result.message)
        assertTrue(result.message.contains("${deep.length} characters"), result.message)
        assertFalse(report.violations.single { it.focusNode == ex("b") }.isUndecided)
        // Only the long value went to the pattern evaluation thread.
        assertEquals(1L, validator.patternWorkerEvaluations)

        val rdf = report.toShaclValidationReportRdf().getTriples()
        assertEquals(listOf<Any>(KastorShaclVocabulary.PatternTooComplex), rdf.filter { it.predicate == KastorShaclVocabulary.resultStatus }.map { it.obj })
    }

    @Test
    fun `stack exhaustion fails validation in strict mode instead of escaping as an Error`() {
        val strict = smallStackValidator(ValidationConfig(strictMode = true))
        val error = assertThrows(ShaclValidationException::class.java) {
            strict.validate(graph("""ex:a a ex:T ; ex:p "$deep" ."""), propertyShape("""sh:pattern "$recursive""""))
        }
        assertTrue(error.message.orEmpty().contains(recursive), error.message)
    }

    /** 4,000 repetitions: more stack than a 512 KiB thread has, a fraction of the 16 MiB of the evaluation thread. */
    private val long = "ab".repeat(2_000)

    @Test
    fun `a long value is decided on the evaluation thread whatever the stack and the nesting of the caller`() {
        // The same pattern at the top of a shape and twenty sh:node levels down, validated on a thread with a small
        // stack: on the calling thread the match would overflow, and the more so the deeper the shape is nested.
        val nested = (1..20).joinToString("\n") { i -> "ex:N$i a sh:NodeShape ; sh:node ex:N${i + 1} ." }
        val shapes = graph(
            """
            ex:Shallow a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:pattern "$recursive" ] .
            ex:Deep a sh:NodeShape ; sh:targetNode ex:a ; sh:node ex:N1 .
            $nested
            ex:N21 a sh:NodeShape ; sh:property [ sh:path ex:p ; sh:pattern "$recursive" ; sh:minLength 1 ] .
            """,
        )
        val validator = validator(ValidationConfig(), frozenClock)
        val report = onThreadWithStack(smallStack) { validator.validate(graph("""ex:a ex:p "$long" ."""), shapes) }
        assertTrue(report.isValid, report.violations.map { it.message }.toString())
        assertEquals(2L, validator.patternWorkerEvaluations, "both pattern constraints were evaluated on the evaluation thread")
        // A value that does not match is decided just the same.
        val failing = onThreadWithStack(smallStack) { validator.validate(graph("""ex:a ex:p "${long}c" ."""), shapes) }
        assertEquals(listOf(false, false), failing.violations.map { it.isUndecided }, failing.violations.map { it.message }.toString())
    }

    @Test
    fun `a match that overflows the stack of the validating thread is repeated on the evaluation thread`() {
        // The seam makes every value start on the validating thread, whose 512 KiB cannot hold this match.
        val validator = validator(ValidationConfig(), frozenClock).also { it.patternInlineMaxLength = Int.MAX_VALUE }
        val shapes = propertyShape("""sh:pattern "$recursive"""")
        val report = onThreadWithStack(smallStack) { validator.validate(graph("""ex:a a ex:T ; ex:p "$long" . ex:b a ex:T ; ex:p "ab" ."""), shapes) }
        assertTrue(report.isValid, report.violations.map { it.message }.toString())
        assertEquals(1L, validator.patternWorkerEvaluations, "the long value overflowed and was repeated; the short one was not")
    }

    @Test
    fun `a value decided on the evaluation thread stays decided for the run`() {
        val data = graph(
            """
            ex:a a ex:T ; ex:p "$long" .
            ex:b a ex:T ; ex:p "$long" .
            ex:c a ex:T ; ex:p "$long" , "ab" .
            """,
        )
        val validator = validator(ValidationConfig(), frozenClock)
        assertTrue(validator.validate(data, propertyShape("""sh:pattern "$recursive"""")).isValid)
        assertEquals(2L, validator.patternEvaluations, "one evaluation of the long value, one of the short one")
        assertEquals(1L, validator.patternWorkerEvaluations)
    }

    @Test
    fun `the step budget is enforced on the evaluation thread too`() {
        // A long hostile value: matched on the evaluation thread, stopped by the step budget, reported as a timeout.
        val hostileLong = "a".repeat(2_000) + "!"
        val validator = validator(ValidationConfig(patternTimeout = Duration.ofMillis(50)), frozenClock)
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p "$hostileLong" . ex:b a ex:T ; ex:p "b" ."""), shapes)
        assertEquals(listOf<Any>(ex("a") to true, ex("b") to false), report.violations.map { it.focusNode to it.isPatternTimeout })
        assertEquals(1L, validator.patternWorkerEvaluations)
    }
}
