package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxRegex
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.base.CoreDatatype
import org.eclipse.rdf4j.query.QueryInterruptedException
import org.eclipse.rdf4j.query.algebra.Coalesce
import org.eclipse.rdf4j.query.algebra.Datatype
import org.eclipse.rdf4j.query.algebra.FunctionCall
import org.eclipse.rdf4j.query.algebra.GroupConcat
import org.eclipse.rdf4j.query.algebra.IRIFunction
import org.eclipse.rdf4j.query.algebra.If
import org.eclipse.rdf4j.query.algebra.Label
import org.eclipse.rdf4j.query.algebra.Lang
import org.eclipse.rdf4j.query.algebra.LocalName
import org.eclipse.rdf4j.query.algebra.MathExpr
import org.eclipse.rdf4j.query.algebra.Namespace
import org.eclipse.rdf4j.query.algebra.Str
import org.eclipse.rdf4j.query.algebra.ValueConstant
import org.eclipse.rdf4j.query.algebra.ValueExpr
import org.eclipse.rdf4j.query.algebra.Var
import org.eclipse.rdf4j.query.algebra.evaluation.QueryValueEvaluationStep
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException
import org.eclipse.rdf4j.query.algebra.evaluation.util.QueryEvaluationUtility
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * What a function of RDF4J's own built-ins may cost in a sandboxed query (see [SandboxLimits]), by IRI. Every
 * function [Rdf4jSandboxFunctions] allows has a cost: [GENERIC] unless listed (fixed-size or linear results, checked
 * after they compute), [COPYING] for results that are (a part of) an argument (not checked, so `SUBSTR` still works on
 * a large literal of the data), and the others checked from their evaluated arguments before they compute
 * (`CONCAT` from the sum of its arguments, case mapping and URI encoding from their largest expansion, a conversion to
 * a number or a date from the length of the lexical form); `REPLACE` runs on [SandboxRegex]. RDF4J registers no
 * power function.
 */
internal enum class Rdf4jCost {
    GENERIC, COPYING, CONCAT, CASE, ENCODE, XSD_CAST, STRDT, REPLACE;

    companion object {
        private const val FN = "http://www.w3.org/2005/xpath-functions#"
        private const val XSD = "http://www.w3.org/2001/XMLSchema#"
        private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

        private val COSTS: Map<String, Rdf4jCost> = buildMap {
            listOf(
                "${FN}substring", "${FN}substring-after", "${FN}substring-before", "${FN}numeric-abs", "${FN}numeric-ceil",
                "${FN}numeric-floor", "${FN}numeric-round", "${XSD}string", "STRLANG", "${RDF}subject", "${RDF}predicate",
                "${RDF}object",
            ).forEach { put(it, COPYING) }
            put("${FN}concat", CONCAT)
            put("${FN}upper-case", CASE)
            put("${FN}lower-case", CASE)
            put("${FN}encode-for-uri", ENCODE)
            put("${FN}replace", REPLACE)
            put("STRDT", STRDT)
        }

        /** The cost of the built-in function [iri] (one [Rdf4jSandboxFunctions.isStandard] allows). */
        fun ofFunction(iri: String): Rdf4jCost = COSTS[iri] ?: if (iri.startsWith(XSD)) XSD_CAST else GENERIC

        /** XSD datatypes whose lexical forms are not parsed into a value of their own (no conversion cost). */
        private val TEXT_TYPES = listOf("string", "normalizedString", "token", "language", "Name", "NCName", "NMTOKEN", "anyURI")
            .map { XSD + it }.toSet()

        /** Whether converting a lexical form to [datatype] parses it into a number, a date or a duration. */
        fun parsesLexicalForm(datatype: String): Boolean = datatype.startsWith(XSD) && datatype !in TEXT_TYPES
    }
}

/**
 * The deadline and interruption that the expressions and statement reads of one sandboxed query check: [deadline] on
 * [clock] ([Long.MAX_VALUE] for none) and the thread's interrupt flag (kept); each fails with
 * [QueryInterruptedException], as RDF4J's own time limit does.
 */
internal class Rdf4jSandboxBudget(private val deadline: Long, private val clock: () -> Long) {
    fun check() {
        if (Thread.currentThread().isInterrupted) throw QueryInterruptedException("The sandboxed query was interrupted")
        if (deadline != Long.MAX_VALUE && clock() - deadline >= 0) throw QueryInterruptedException("The sandboxed query ran out of time")
    }
}

/**
 * A [SandboxLimitException] raised while RDF4J evaluates a sandboxed query, as a query failure. It is deliberately no
 * [QueryEvaluationException]: RDF4J turns those into `false` in a `FILTER`, skips them in `COALESCE` and treats them
 * as "equal" when it sorts (each of which could hide a SHACL violation); this one ends the query.
 */
internal class Rdf4jSandboxLimitExceeded(cause: SandboxLimitException) : RuntimeException(cause.message, cause)

/**
 * The bounds of the value expressions of one sandboxed query on RDF4J ([KastorEvaluationStrategy] applies them to
 * every step it precompiles): [budget] before each expression runs, the operands of a multiplication or division,
 * the results against [SandboxLimits] (unless an expression only copies an argument), the characters of
 * `GROUP_CONCAT`, and the arguments of the costly built-ins ([Rdf4jCost]) before they compute.
 */
internal class Rdf4jEvaluationBounds(val budget: Rdf4jSandboxBudget, private val valueFactory: ValueFactory) {

    /** Characters the `GROUP_CONCAT` aggregates of this query accumulated. */
    private val groupConcat = AtomicLong()

    /** [step], the precompiled [expr], bounded. */
    fun wrap(expr: ValueExpr, step: QueryValueEvaluationStep): QueryValueEvaluationStep {
        val parent = expr.parentNode
        val operand = parent is MathExpr && (parent.operator == MathExpr.MathOp.MULTIPLY || parent.operator == MathExpr.MathOp.DIVIDE)
        val counted = parent is GroupConcat && parent.arg === expr
        val leaf = expr is Var || expr is ValueConstant
        if (leaf && !operand && !counted) return step
        val checked = !leaf && !copying(expr)
        return QueryValueEvaluationStep { bindings ->
            failing {
                budget.check()
                val value = step.evaluate(bindings)
                if (checked) checkResult(value, describe(expr))
                if (operand) checkOperand(value)
                if (counted) count(value)
                value
            }
        }
    }

    /** Fails with [SandboxLimitException] when calling the built-in [iri] of [cost] on [args] would exceed a limit. */
    fun precheck(cost: Rdf4jCost, iri: String, args: List<Value>) {
        when (cost) {
            Rdf4jCost.CONCAT -> SandboxLimits.checkStringLength(args.sumOf { label(it).toLong() }, iri)
            Rdf4jCost.CASE -> SandboxLimits.checkStringLength((args.firstOrNull()?.let(::label) ?: 0) * 3L, iri)
            Rdf4jCost.ENCODE -> SandboxLimits.checkStringLength((args.firstOrNull()?.let(::label) ?: 0) * 9L, iri)
            Rdf4jCost.XSD_CAST -> args.firstOrNull()?.let { SandboxLimits.checkNumericLexical(label(it), iri) }
            Rdf4jCost.STRDT -> if (args.size == 2 && args[1] is IRI && Rdf4jCost.parsesLexicalForm(args[1].stringValue())) {
                SandboxLimits.checkNumericLexical(label(args[0]), iri)
            }
            else -> Unit
        }
    }

    /** [block], with a [SandboxLimitException] it raises turned into [Rdf4jSandboxLimitExceeded]. */
    fun <T> failing(block: () -> T): T = try {
        block()
    } catch (e: SandboxLimitException) {
        throw Rdf4jSandboxLimitExceeded(e)
    }

    /** `REGEX(text, pattern, flags)` as RDF4J evaluates it (its argument checks and flags), on [SandboxRegex]. */
    fun regex(text: Value, pattern: Value, flags: Value?, compiled: PatternCache): Value {
        if (!QueryEvaluationUtility.isStringLiteral(text) || !QueryEvaluationUtility.isSimpleLiteral(pattern) ||
            (flags != null && !QueryEvaluationUtility.isSimpleLiteral(flags))
        ) {
            throw ValueExprEvaluationException("REGEX: the text must be a string literal, the pattern and flags simple literals")
        }
        val regex = compiled.get(pattern.stringValue(), flags?.stringValue(), ::regexFlags)
        return valueFactory.createLiteral(SandboxRegex.find(regex, (text as Literal).label, Runnable { budget.check() }))
    }

    /** `fn:replace(text, pattern, replacement, flags)` as RDF4J's `Replace` evaluates it, on [SandboxRegex]. */
    fun replace(args: List<Value>, compiled: PatternCache): Value {
        if (args.size !in 3..4) throw ValueExprEvaluationException("REPLACE: wrong number of arguments")
        val text = args[0]
        if (text !is Literal || !QueryEvaluationUtility.isStringLiteral(text) || args.drop(1).any { !QueryEvaluationUtility.isSimpleLiteral(it) }) {
            throw ValueExprEvaluationException("REPLACE: the text must be a string literal, the other arguments simple literals")
        }
        val regex = compiled.get(args[1].stringValue(), args.getOrNull(3)?.stringValue(), ::replaceFlags)
        val result = try {
            SandboxRegex.replace(regex, text.label, args[2].stringValue(), skipEmptyAfterFirst = false, check = Runnable { budget.check() })
        } catch (e: IllegalArgumentException) {
            throw ValueExprEvaluationException("REPLACE: ${e.message}", e)
        } catch (e: IndexOutOfBoundsException) {
            throw ValueExprEvaluationException("REPLACE: ${e.message}", e)
        } ?: text.label
        val language = text.language
        return when {
            language.isPresent -> valueFactory.createLiteral(result, language.get())
            text.datatype != null -> valueFactory.createLiteral(result, text.datatype)
            else -> valueFactory.createLiteral(result)
        }
    }

    /** The last compiled pattern of one call site and the text and flags it was compiled from. */
    class PatternCache {
        private class Compiled(val text: String, val flags: String?, val pattern: Pattern)

        @Volatile private var last: Compiled? = null

        fun get(text: String, flags: String?, toFlags: (String?) -> Int): Pattern {
            last?.let { if (it.text == text && it.flags == flags) return it.pattern }
            val pattern = try {
                Pattern.compile(text, toFlags(flags))
            } catch (e: java.util.regex.PatternSyntaxException) {
                throw ValueExprEvaluationException(e.message, e)
            }
            return pattern.also { last = Compiled(text, flags, it) }
        }
    }

    private fun checkResult(value: Value, what: String) {
        when (value) {
            is Literal -> when {
                value.isIntegerValued() -> SandboxLimits.checkIntegerBits(value.integerValue().bitLength().toLong(), what)
                value.isDecimalValued() -> value.decimalValue().let {
                    SandboxLimits.checkDecimal(it.unscaledValue().bitLength().toLong(), it.scale().toLong(), what)
                }
                else -> SandboxLimits.checkStringLength(value.label.length.toLong(), what)
            }
            is IRI -> SandboxLimits.checkStringLength(value.stringValue().length.toLong(), what)
            is Triple -> if (terms(value, MAX_TRIPLE_TERM_NODES + 1) > MAX_TRIPLE_TERM_NODES) {
                throw SandboxLimitException("$what would compute a triple term of more than $MAX_TRIPLE_TERM_NODES terms")
            }
        }
    }

    private fun checkOperand(value: Value) {
        if (value !is Literal) return
        SandboxLimits.checkNumericLexical(value.label.length, "a multiplication or division")
        if (value.isIntegerValued()) SandboxLimits.checkIntegerBits(value.integerValue().bitLength().toLong(), "a multiplication or division")
        else if (value.isDecimalValued()) value.decimalValue().let {
            SandboxLimits.checkDecimal(it.unscaledValue().bitLength().toLong(), it.scale().toLong(), "a multiplication or division")
        }
    }

    private fun count(value: Value) {
        val total = groupConcat.addAndGet(label(value) + 1L)
        if (total > SandboxLimits.MAX_GROUP_CONCAT_LENGTH) {
            throw SandboxLimitException("GROUP_CONCAT would accumulate more than ${SandboxLimits.MAX_GROUP_CONCAT_LENGTH} characters")
        }
    }

    private fun label(value: Value): Int = if (value is Literal) value.label.length else 0

    private fun Literal.isIntegerValued() = (coreDatatype as? CoreDatatype.XSD)?.isIntegerDatatype == true

    private fun Literal.isDecimalValued() = coreDatatype == CoreDatatype.XSD.DECIMAL

    private fun terms(value: Value, limit: Int): Int {
        if (value !is Triple) return 1
        var count = 1
        for (part in listOf(value.subject, value.predicate, value.`object`)) {
            count += terms(part, limit - count)
            if (count >= limit) return count
        }
        return count
    }

    private companion object {
        /** Most terms a computed triple term may hold (a chain of `TRIPLE(?t, :p, ?t)` doubles them at every step). */
        const val MAX_TRIPLE_TERM_NODES = 4096

        fun copying(expr: ValueExpr): Boolean = when (expr) {
            is Str, is Label, is Lang, is Datatype, is Namespace, is LocalName, is IRIFunction, is If, is Coalesce -> true
            is FunctionCall -> Rdf4jCost.ofFunction(expr.uri) == Rdf4jCost.COPYING
            else -> false
        }

        fun describe(expr: ValueExpr): String = (expr as? FunctionCall)?.uri ?: expr.signature

        /** RDF4J's `REGEX` flags: `s`, `m`, `i` (Unicode-aware), `x`, `d`, `u` and `q`; other characters are ignored. */
        fun regexFlags(flags: String?): Int = (flags ?: "").fold(0) { mask, c ->
            mask or when (c) {
                's' -> Pattern.DOTALL
                'm' -> Pattern.MULTILINE
                'i' -> Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                'x' -> Pattern.COMMENTS
                'd' -> Pattern.UNIX_LINES
                'u' -> Pattern.UNICODE_CASE
                'q' -> Pattern.LITERAL
                else -> 0
            }
        }

        /** RDF4J's `fn:replace` flags: `s`, `m`, `i`, `x`, `d` and `u`; any other character is an error. */
        fun replaceFlags(flags: String?): Int = (flags ?: "").fold(0) { mask, c ->
            mask or when (c) {
                's' -> Pattern.DOTALL
                'm' -> Pattern.MULTILINE
                'i' -> Pattern.CASE_INSENSITIVE
                'x' -> Pattern.COMMENTS
                'd' -> Pattern.UNIX_LINES
                'u' -> Pattern.UNICODE_CASE
                else -> throw ValueExprEvaluationException("REPLACE: flag character '$c' is not allowed")
            }
        }
    }
}
