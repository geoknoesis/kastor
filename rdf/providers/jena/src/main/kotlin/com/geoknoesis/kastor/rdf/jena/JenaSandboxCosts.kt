package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxRegex
import com.geoknoesis.kastor.rdf.sparql.internal.XPathPattern
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegexException
import org.apache.jena.graph.NodeFactory
import org.apache.jena.sparql.expr.*
import org.apache.jena.sparql.expr.nodevalue.NodeValueOps
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * What an expression of a sandboxed query may cost on Jena (see [SandboxLimits]), by Jena expression class and by
 * function IRI: the **allowlist** of what a sandboxed query may evaluate, each entry with the bound it is held to.
 *
 * - [GENERIC]: linear in its arguments and fixed-size results (comparisons, tests, dates, hashes, `NOW`, `RAND`,
 *   `UUID`). Its result is checked against the limits ([JenaSandboxBounds.checkResult]).
 * - [COPYING]: the result is (a part of) an argument (`STR`, `SUBSTR`, `IF`, `COALESCE`): not checked, so a function
 *   such as `STR` still works on a large literal of the data. `IRI` resolves against a base, so it is checked.
 * - The others are checked **before** they compute, from their evaluated arguments: a power from the bits of its
 *   result, a multiplication, division or modulo from its operands, `CONCAT` from the sum of its arguments, case
 *   mapping, URI encoding and Unicode normalization from their largest expansion, a conversion to a number, date or
 *   duration from the length of the lexical form; `REGEX` and `REPLACE` run on [SandboxRegex].
 *
 * Non-determinism is SPARQL's own (`NOW`, `RAND`, `UUID`, `STRUUID`, `BNODE`): allowed, and bounded in size.
 */
internal enum class JenaCost {
    GENERIC, COPYING, POWER, EXP10, MULTIPLICATIVE, CONCAT, CASE, ENCODE, NORMALIZE_UNICODE, XSD_CAST, STRDT, ROUND, REGEX, REPLACE;

    /** Whether the arguments are evaluated first, to check the cost before computing (or to compute it here). */
    val evaluatesArguments: Boolean get() = this != GENERIC && this != COPYING

    companion object {
        private const val XSD = "http://www.w3.org/2001/XMLSchema#"
        private const val FN = "http://www.w3.org/2005/xpath-functions#"
        private const val MATH = "http://www.w3.org/2005/xpath-functions/math#"
        private const val SPARQL = "http://www.w3.org/ns/sparql#"

        /** The Jena expression classes a sandboxed query may contain, besides `E_Function` (see [ofFunction]). */
        private val CLASSES: Map<Class<out ExprFunction>, JenaCost> = buildMap {
            listOf<KClass<out ExprFunction>>(
                E_Str::class, E_StrSubstring::class, E_StrBefore::class, E_StrAfter::class, E_StrLang::class,
                E_StrLangDir::class, E_BNode.BNode0::class, E_BNode.BNode1::class,
                E_Coalesce::class, E_If::class, E_Lang::class, E_LangDir::class, E_Datatype::class, E_TripleSubject::class,
                E_TriplePredicate::class, E_TripleObject::class, E_UnaryPlus::class, E_UnaryMinus::class, E_NumAbs::class,
                E_NumRound::class, E_NumCeiling::class, E_NumFloor::class,
            ).forEach { put(it.java, COPYING) }
            listOf<KClass<out ExprFunction>>(E_Multiply::class, E_Divide::class, E_OpNumericIntegerDivide::class, E_OpNumericMod::class)
                .forEach { put(it.java, MULTIPLICATIVE) }
            put(E_StrConcat::class.java, CONCAT)
            put(E_StrUpperCase::class.java, CASE)
            put(E_StrLowerCase::class.java, CASE)
            put(E_StrEncodeForURI::class.java, ENCODE)
            put(E_StrDatatype::class.java, STRDT)
            put(E_Cast::class.java, STRDT)
            put(E_Regex::class.java, REGEX)
            put(E_StrReplace::class.java, REPLACE)
            listOf<KClass<out ExprFunction>>(
                E_Add::class, E_Subtract::class, E_Equals::class, E_NotEquals::class, E_LessThan::class,
                E_LessThanOrEqual::class, E_GreaterThan::class, E_GreaterThanOrEqual::class, E_LogicalAnd::class,
                E_LogicalOr::class, E_LogicalNot::class, E_Bound::class, E_OneOf::class, E_NotOneOf::class, E_EBV::class,
                E_SameTerm::class, E_SameValue::class, E_IsBlank::class, E_IsIRI::class, E_IsURI::class, E_IsLiteral::class,
                E_IsNumeric::class, E_IsTriple::class, E_HasLang::class, E_HasLangDir::class, E_LangMatches::class,
                E_StrLength::class, E_StrContains::class, E_StrStartsWith::class, E_StrEndsWith::class, E_MD5::class,
                E_SHA1::class, E_SHA224::class, E_SHA256::class, E_SHA384::class, E_SHA512::class, E_Now::class,
                E_Random::class, E_UUID::class, E_StrUUID::class, E_Version::class, E_TripleFn::class,
                E_DateTimeYear::class, E_DateTimeMonth::class, E_DateTimeDay::class, E_DateTimeHours::class,
                E_DateTimeMinutes::class, E_DateTimeSeconds::class, E_DateTimeTimezone::class, E_DateTimeTZ::class,
                E_AdjustToTimezone::class, E_Function::class, E_IRI::class, E_IRI2::class, E_URI::class, E_URI2::class,
            ).forEach { put(it.java, GENERIC) }
        }

        /**
         * The registered functions a sandboxed query may call, by IRI: every function Jena registers in the XSD,
         * XPath (`fn:`, `math:`) and SPARQL namespaces that the provider has reviewed, with its cost. Jena's
         * registrations in these namespaces that are left out ([EXCLUDED]) are unknown functions to a sandboxed query.
         */
        val FUNCTIONS: Map<String, JenaCost> = buildMap {
            val copying = listOf(
                "${FN}substring", "${FN}substring-before", "${FN}substring-after", "${FN}normalize-space", "${FN}abs",
                "${FN}ceiling", "${FN}floor", "${XSD}string", "${XSD}anyURI",
            ) + listOf(
                "str", "substr", "strbefore", "strafter", "strlang", "strlangdir", "bnode", "lang", "langdir",
                "datatype", "subject", "predicate", "object", "abs", "ceil", "floor", "round", "unary-minus", "unary-plus",
            ).map { SPARQL + it }
            copying.forEach { put(it, COPYING) }
            put("${MATH}pow", POWER)
            put("${MATH}exp10", EXP10)
            listOf("${FN}numeric-integer-divide", "${FN}numeric-mod", "${SPARQL}multiply", "${SPARQL}divide").forEach { put(it, MULTIPLICATIVE) }
            listOf("${FN}concat", "${SPARQL}concat").forEach { put(it, CONCAT) }
            listOf("${FN}upper-case", "${FN}lower-case", "${SPARQL}ucase", "${SPARQL}lcase").forEach { put(it, CASE) }
            listOf("${FN}encode-for-uri", "${SPARQL}encode").forEach { put(it, ENCODE) }
            put("${FN}normalize-unicode", NORMALIZE_UNICODE)
            listOf("${FN}round", "${FN}round-half-to-even").forEach { put(it, ROUND) }
            listOf("${FN}matches", "${SPARQL}regex").forEach { put(it, REGEX) }
            listOf("${FN}replace", "${SPARQL}replace").forEach { put(it, REPLACE) }
            put("${SPARQL}strdt", STRDT)
            listOf(
                "boolean", "byte", "date", "dateTime", "dayTimeDuration", "decimal", "double", "duration", "float", "gDay",
                "gMonth", "gMonthDay", "gYear", "gYearMonth", "int", "integer", "long", "negativeInteger",
                "nonNegativeInteger", "nonPositiveInteger", "positiveInteger", "short", "time", "unsignedInt",
                "unsignedLong", "unsignedShort", "yearMonthDuration",
            ).forEach { put(XSD + it, XSD_CAST) }
            listOf(
                "adjust-date-to-timezone", "adjust-dateTime-to-timezone", "adjust-time-to-timezone", "boolean", "contains",
                "dateTime", "day-from-date", "day-from-dateTime", "days-from-date", "days-from-dateTime",
                "days-from-duration", "ends-with", "error", "format-number", "hours-from-dateTime", "hours-from-duration",
                "hours-from-time", "implicit-timezone", "minutes-from-dateTime", "minutes-from-duration",
                "minutes-from-time", "month-from-date", "month-from-dateTime", "months-from-date", "months-from-dateTime",
                "months-from-duration", "not", "seconds-from-dateTime", "seconds-from-duration", "seconds-from-time",
                "starts-with", "string-length", "timezone-from-date", "timezone-from-dateTime", "timezone-from-time",
                "year-from-date", "year-from-dateTime", "years-from-date", "years-from-dateTime", "years-from-duration",
            ).forEach { put(FN + it, GENERIC) }
            listOf("acos", "asin", "atan", "atan2", "cos", "exp", "log", "log10", "pi", "sin", "sqrt", "tan")
                .forEach { put(MATH + it, GENERIC) }
            listOf(
                "add", "and", "contains", "day", "equals", "greater-than", "greater-than-or-equals", "haslang", "haslangdir",
                "hours", "iri", "uri", "isBlank", "isIRI", "isLiteral", "isNumeric", "isTriple", "isURI", "langMatches", "less-than",
                "less-than-or-equal", "md5", "minus", "minutes", "month", "not", "not-equals", "now", "or", "plus", "rand",
                "sameTerm", "sameValue", "seconds", "sha1", "sha224", "sha256", "sha384", "sha512", "strends", "strlen",
                "strstarts", "struuid", "subtract", "timezone", "triple", "tz", "uuid", "year",
            ).forEach { put(SPARQL + it, GENERIC) }
        }

        /**
         * Functions Jena registers in the standard namespaces that a sandboxed query may not call: `fn:apply` calls a
         * function by an IRI computed at run time (as ARQ's `CALL`, refused before evaluation), and the size of an
         * `fn:collation-key` result is not bounded by its argument in a documented way.
         */
        val EXCLUDED: Set<String> = setOf("${FN}apply", "${FN}collation-key")

        /** XSD datatypes whose lexical forms are not parsed into a value of their own (no conversion cost). */
        private val TEXT_TYPES = listOf("string", "normalizedString", "token", "language", "Name", "NCName", "NMTOKEN", "anyURI")
            .map { XSD + it }.toSet()

        /** Whether converting a lexical form to [datatype] parses it into a number, a date or a duration. */
        fun parsesLexicalForm(datatype: String): Boolean = datatype.startsWith(XSD) && datatype !in TEXT_TYPES

        /** The cost of [function], or null when a sandboxed query may not contain it. */
        fun of(function: ExprFunction): JenaCost? =
            if (function is E_Function) ofFunction(function.functionIRI) ?: GENERIC else CLASSES[function.javaClass]

        /**
         * The cost of a call of [iri]: a registered standard function's, or null for any other IRI (a custom function
         * of the query, bounded by its own nested queries, or an unknown function, which is a SPARQL error).
         */
        fun ofFunction(iri: String): JenaCost? = FUNCTIONS[iri]
    }
}

/**
 * The cost-specific evaluation of [JenaCost]s whose arguments are evaluated first: the checks before computing, and
 * `REGEX` / `REPLACE` on [SandboxRegex] (XPath regular expressions and flags, on a linear-time engine) with Jena's
 * argument checks, its result datatype, and its rule that an empty match after the first is not replaced. The `check` of [regex] and [replace]
 * is the query's deadline and cancellation, run while the regular expression works.
 */
internal class JenaCostEvaluation(private val cost: JenaCost, private val label: String) {

    /** The patterns this call site compiled, by text and flags (patterns are usually constant); bounded. */
    private val compiled = SandboxRegex.PatternCache()

    /** Fails with [SandboxLimitException] when computing on [args] would exceed a limit. */
    fun precheck(args: List<NodeValue>) {
        when (cost) {
            JenaCost.POWER -> if (args.size == 2 && args[0].isInteger && args[1].isInteger) power(args[0].integer, args[1].integer)
            JenaCost.EXP10 -> if (args.size == 1 && args[0].isInteger) power(BigInteger.TEN, args[0].integer)
            JenaCost.MULTIPLICATIVE -> args.forEach(::operand)
            JenaCost.CONCAT -> SandboxLimits.checkStringLength(args.sumOf { JenaSandboxBounds.textLength(it.asNode()).toLong() }, label)
            JenaCost.CASE -> expansion(args, 3)
            JenaCost.ENCODE -> expansion(args, 9)
            JenaCost.NORMALIZE_UNICODE -> expansion(args, 18)
            JenaCost.XSD_CAST -> args.firstOrNull()?.let { SandboxLimits.checkNumericLexical(lexicalLength(it), label) }
            JenaCost.STRDT -> if (args.size == 2 && args[1].isIRI && JenaCost.parsesLexicalForm(args[1].asNode().uri)) {
                SandboxLimits.checkNumericLexical(lexicalLength(args[0]), label)
            }
            // The precision becomes the scale of the result (fn:round-half-to-even(1.5, 2147483647) would allocate it).
            JenaCost.ROUND -> if (args.size == 2 && args[1].isInteger) {
                val precision = args[1].integer
                if (precision.abs() > BigInteger.valueOf(SandboxLimits.MAX_DECIMAL_SCALE.toLong())) {
                    throw SandboxLimitException("$label: a precision of $precision is beyond the limit of ${SandboxLimits.MAX_DECIMAL_SCALE}")
                }
            }
            else -> Unit
        }
    }

    /** As Jena's `math:pow` and `math:exp10`, which compute `BigInteger.pow(exponent.intValue())` for integers. */
    private fun power(base: BigInteger, exponent: BigInteger) {
        val e = exponent.toInt()
        if (e > 0) SandboxLimits.checkIntegerBits(SandboxLimits.powerBits(base, e.toLong()), label)
    }

    private fun operand(value: NodeValue) {
        if (value.isInteger) SandboxLimits.checkIntegerBits(value.integer.bitLength().toLong(), label)
        else if (value.isDecimal) value.decimal.let { SandboxLimits.checkDecimal(it.unscaledValue().bitLength().toLong(), it.scale().toLong(), label) }
    }

    private fun expansion(args: List<NodeValue>, factor: Int) =
        SandboxLimits.checkStringLength((args.firstOrNull()?.let(::lexicalLength) ?: 0).toLong() * factor, label)

    private fun lexicalLength(value: NodeValue): Int = value.asNode().let { if (it.isLiteral) it.literalLexicalForm.length else 0 }

    /** `REGEX` / `fn:matches` on [args] (text, pattern, flags), as Jena's `E_Regex`. */
    fun regex(args: List<NodeValue>, check: Runnable): NodeValue {
        if (args.size !in 2..3) throw ExprEvalException("$label: wrong number of arguments")
        val text = NodeValueOps.checkAndGetStringLiteral(label, args[0]).literalLexicalForm
        if (!args[1].isString || (args.size == 3 && !args[2].isString)) throw ExprEvalException("$label: the pattern and flags must be strings")
        val pattern = pattern(args[1].string, args.getOrNull(2)?.string, check)
        return NodeValue.makeBoolean(SandboxRegex.find(pattern, text, check))
    }

    /**
     * `REPLACE` / `fn:replace` on [args] (text, pattern, replacement, flags), as Jena's `XSDFuncOp.strReplace`: the
     * result keeps the language tag, direction and datatype of the text, and the text itself is returned when nothing
     * changed. A replacement that refers to a missing group is a SPARQL error (Jena fails the query for some).
     */
    fun replace(args: List<NodeValue>, check: Runnable): NodeValue {
        if (args.size !in 3..4) throw ExprEvalException("$label: wrong number of arguments")
        val node = NodeValueOps.checkAndGetStringLiteral(label, args[0])
        val patternText = NodeValueOps.checkAndGetStringLiteral(label, args[1]).literalLexicalForm
        val replacement = NodeValueOps.checkAndGetStringLiteral(label, args[2]).literalLexicalForm
        val flags = args.getOrNull(3)?.let { NodeValueOps.checkAndGetStringLiteral(label, it).literalLexicalForm }
        val text = node.literalLexicalForm
        val compiledPattern = pattern(patternText, flags, check)
        val result = try {
            SandboxRegex.replace(compiledPattern, text, replacement, skipEmptyAfterFirst = true, check = check)
        } catch (e: XPathRegexException) {
            throw ExprEvalException("$label: ${e.message}", e)
        }
        if (result == null || result == text) return args[0]
        return NodeValue.makeNode(NodeFactory.createLiteral(result, node.literalLanguage, node.literalBaseDirection, node.literalDatatype))
    }

    /**
     * [text] compiled with the XPath [flags] for the linear-time engine ([SandboxRegex.compile], running [check]),
     * from the call site's cache. An invalid pattern or flag is a SPARQL error; one the engine cannot honour (a
     * back-reference, a lookaround, a pattern too complex to compile, ...) fails the query.
     */
    private fun pattern(text: String, flags: String?, check: Runnable): XPathPattern {
        return try {
            compiled.get(text, flags, check)
        } catch (e: XPathRegexException) {
            throw ExprEvalException("$label: ${e.message}", e)
        }
    }
}
