package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.orderViolations
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.literalLexicalString
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMaxExclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMaxInclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMinExclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMinInclusive
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermEquals
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint
import com.geoknoesis.kastor.rdf.shacl.native.distinctShaclTerms

private val singleLineBreakRegex = Regex("[\\f\\r\\n\\u000B]")

internal fun NativeShaclValidator.evaluateConstraint(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    ctx: NativeValidationContext,
    state: DepthState,
    currentShape: RdfResource,
    path: ShaclPath?,
    sink: Sink,
) {
    val data = ctx.data
    val pathPredicate = tpl.path?.predicate

    fun add(
        type: ConstraintType,
        message: String,
        value: RdfTerm? = null,
        params: Map<String, Any> = emptyMap(),
        severity: ViolationSeverity = tpl.severity,
        severityIri: Iri? = tpl.severityCustomIri,
        messages: List<Literal> = tpl.messages,
        sourceConstraint: RdfTerm? = null,
    ) {
        sink.failed = true
        if (sink.reports) {
            sink.results.add(violation(focus, tpl, constraintStub(type, pathPredicate, params), message, value, severity, severityIri, messages, sourceConstraint))
        }
    }

    /** Visits the value nodes, stopping at the first definite failure of a [Mode.CONFORMS] evaluation. */
    fun eachValue(action: (RdfTerm) -> Unit) {
        for (v in values) {
            if (sink.stop) return
            action(v)
        }
    }

    fun fingerprints(terms: List<RdfTerm>): Set<String> = terms.mapTo(HashSet()) { shaclRdfTermFingerprint(it) }

    when (c) {
        // Cardinality results carry no sh:value (SHACL §4.2.1 / §4.2.2).
        is PropertyConstraint.MinCount ->
            if (values.size < c.n) add(ConstraintType.MIN_COUNT, "Minimum cardinality ${c.n} required, found ${values.size}", params = mapOf("min" to c.n, "actual" to values.size))
        is PropertyConstraint.MaxCount ->
            if (values.size > c.n) add(ConstraintType.MAX_COUNT, "Maximum cardinality ${c.n} allowed, found ${values.size}", params = mapOf("max" to c.n, "actual" to values.size))
        is PropertyConstraint.Datatype ->
            eachValue { v ->
                if (!literalMatchesShaclDatatypes(v, c.allowed)) {
                    add(
                        ConstraintType.DATATYPE,
                        "Value $v does not match allowed datatype(s) ${c.allowed.joinToString { it.value }}",
                        v,
                        severity = c.severityOverride ?: tpl.severity,
                        severityIri = if (c.severityOverride != null) null else tpl.severityCustomIri,
                        messages = c.messages.ifEmpty { tpl.messages },
                    )
                }
            }
        is PropertyConstraint.Class ->
            eachValue { v ->
                val ok = v is RdfResource && data.typesOf(v).any { t -> c.iri in data.superclassCone(t) }
                if (!ok) add(ConstraintType.CLASS, "Expected rdf:type (subclass of) ${c.iri.value} for value $v", v)
            }
        is PropertyConstraint.ClassAnyOf ->
            eachValue { v ->
                val ok = v is RdfResource && c.options.any { req -> data.typesOf(v).any { t -> req in data.superclassCone(t) } }
                if (!ok) add(ConstraintType.CLASS, "Expected rdf:type matching one of ${c.options.joinToString { it.value }} for value $v", v)
            }
        is PropertyConstraint.NodeKind ->
            eachValue { v ->
                if (!c.kinds.any { matchesNodeKind(v, it) }) add(ConstraintType.NODE_KIND, "Node kind ${c.kinds.joinToString { it.value }} required for $v", v)
            }
        is PropertyConstraint.Pattern ->
            eachValue { v ->
                val lex = literalLexicalString(v)
                val outcome = if (lex == null) PatternOutcome.NO_MATCH else matchPattern(c, lex, currentShape, ctx)
                when {
                    outcome.undecided != null -> sink.undecidedPattern(focus, tpl, v, outcome.undecided)
                    !outcome.matches -> add(ConstraintType.PATTERN, "Pattern ${c.pattern} violated for value $v", v)
                }
            }
        is PropertyConstraint.MinLength ->
            eachValue { v ->
                val lex = literalLexicalString(v)
                if (lex == null || lex.codePointCount(0, lex.length) < c.n) add(ConstraintType.MIN_LENGTH, "minLength ${c.n} violated for $v", v)
            }
        is PropertyConstraint.MaxLength ->
            eachValue { v ->
                val lex = literalLexicalString(v)
                if (lex == null || lex.codePointCount(0, lex.length) > c.n) add(ConstraintType.MAX_LENGTH, "maxLength ${c.n} violated for $v", v)
            }
        is PropertyConstraint.In ->
            eachValue { v ->
                if (!c.allowed.any { shaclRdfTermEquals(it, v) }) add(ConstraintType.IN, "Value $v not in sh:in", v)
            }
        is PropertyConstraint.HasValue ->
            if (values.none { shaclRdfTermEquals(it, c.value) }) add(ConstraintType.HAS_VALUE, "sh:hasValue missing ${c.value}")
        is PropertyConstraint.LanguageIn ->
            eachValue { v ->
                val lang = (v as? LangString)?.lang?.takeIf { it.isNotEmpty() }
                val ok = lang != null && c.langs.any { allowed -> languageTagMatchesLanguageRange(lang, allowed) }
                if (!ok) add(ConstraintType.LANGUAGE_IN, "languageIn violated for $v", v)
            }
        is PropertyConstraint.UniqueLang ->
            if (c.enabled) {
                // Language tags are compared case-insensitively.
                val duplicated = values.mapNotNull { v -> (v as? LangString)?.let { "${it.lang.lowercase()}\u0000${it.direction?.token ?: ""}" } }
                    .groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                duplicated.forEach { _ -> add(ConstraintType.UNIQUE_LANG, "sh:uniqueLang violated") }
            }
        is PropertyConstraint.EqualsPath -> {
            val other = PathEvaluator.evaluate(focus, c.otherPath, data)
            val otherKeys = fingerprints(other)
            val valueKeys = fingerprints(values)
            values.filter { shaclRdfTermFingerprint(it) !in otherKeys }.forEach { add(ConstraintType.EQUALS, "sh:equals: value $it is missing from the referenced path", it) }
            other.filter { shaclRdfTermFingerprint(it) !in valueKeys }.forEach { add(ConstraintType.EQUALS, "sh:equals: referenced value $it is missing from the path", it) }
        }
        is PropertyConstraint.DisjointPath -> {
            val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
            values.filter { shaclRdfTermFingerprint(it) in otherKeys }.forEach { add(ConstraintType.DISJOINT, "sh:disjoint violated: $it is shared with the referenced path", it) }
        }
        // One result per (value, other value) pair that is not ordered; orderViolations sorts once when it can.
        is PropertyConstraint.LessThanPath ->
            for ((v, w) in orderViolations(values, PathEvaluator.evaluate(focus, c.otherPath, data), strict = true)) {
                add(ConstraintType.LESS_THAN, "sh:lessThan violated comparing $v and $w", v)
                if (sink.stop) break
            }
        is PropertyConstraint.LessThanOrEqualsPath ->
            for ((v, w) in orderViolations(values, PathEvaluator.evaluate(focus, c.otherPath, data), strict = false)) {
                add(ConstraintType.LESS_THAN_OR_EQUALS, "sh:lessThanOrEquals violated comparing $v and $w", v)
                if (sink.stop) break
            }
        is PropertyConstraint.MinInclusive ->
            eachValue { v -> if (!satisfiesMinInclusive(v, c.bound)) add(ConstraintType.MIN_INCLUSIVE, "minInclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.MaxInclusive ->
            eachValue { v -> if (!satisfiesMaxInclusive(v, c.bound)) add(ConstraintType.MAX_INCLUSIVE, "maxInclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.MinExclusive ->
            eachValue { v -> if (!satisfiesMinExclusive(v, c.bound)) add(ConstraintType.MIN_EXCLUSIVE, "minExclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.MaxExclusive ->
            eachValue { v -> if (!satisfiesMaxExclusive(v, c.bound)) add(ConstraintType.MAX_EXCLUSIVE, "maxExclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.Qualified -> {
            // SHACL §4.7.3: with sh:qualifiedValueShapesDisjoint, values conforming to a sibling shape don't count.
            // The count lies in [definite, possible]; only an outcome that depends on undefined answers is undefined.
            // A maximum makes the shape a negative dependency; sibling exclusion always is one.
            var definite = 0
            var possible = 0
            var undecided: Pair<RdfTerm, RdfResource>? = null
            for (v in values) {
                val own = conformance(v, c.shape, ctx, state, negative = c.max != null)
                var counted = own
                var question: RdfResource? = if (own == Conformance.UNDEFINED) c.shape else null
                if (c.disjoint && (own != Conformance.FAILS || sink.exhaustive)) {
                    for (s in c.siblings) {
                        when (conformance(v, s, ctx, state, negative = true)) {
                            Conformance.CONFORMS -> counted = Conformance.FAILS
                            Conformance.UNDEFINED -> if (counted != Conformance.FAILS) {
                                counted = Conformance.UNDEFINED
                                if (question == null || prefersUndefined(ctx, v to question, v, s)) question = s
                            }
                            Conformance.FAILS -> Unit
                        }
                        if (counted == Conformance.FAILS && !sink.exhaustive) break
                    }
                }
                when (counted) {
                    Conformance.CONFORMS -> { definite++; possible++ }
                    Conformance.UNDEFINED -> {
                        possible++
                        val asked = question ?: c.shape
                        if (prefersUndefined(ctx, undecided, v, asked)) undecided = v to asked
                    }
                    Conformance.FAILS -> Unit
                }
            }
            val u = undecided
            if (c.min != null) {
                if (possible < c.min) {
                    add(ConstraintType.QUALIFIED_MIN_COUNT, "qualifiedMinCount violated (required ${c.min}, found $definite)", params = mapOf("min" to c.min, "actual" to definite))
                } else if (definite < c.min && u != null) {
                    sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.QUALIFIED_MIN_COUNT, null, u.first, u.second)
                }
            }
            if (c.max != null) {
                if (definite > c.max) {
                    add(ConstraintType.QUALIFIED_MAX_COUNT, "qualifiedMaxCount violated (max ${c.max}, found $definite)", params = mapOf("max" to c.max, "actual" to definite))
                } else if (possible > c.max && u != null) {
                    sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.QUALIFIED_MAX_COUNT, null, u.first, u.second)
                }
            }
        }
        is PropertyConstraint.MinListLength ->
            eachValue { v ->
                val members = data.expandDataList(v)
                if (members == null || members.size < c.n) add(ConstraintType.MIN_LIST_LENGTH, "sh:minListLength requires at least ${c.n} list members", v)
            }
        is PropertyConstraint.MaxListLength ->
            eachValue { v ->
                val members = data.expandDataList(v)
                if (members == null || members.size > c.n) add(ConstraintType.MAX_LIST_LENGTH, "sh:maxListLength allows at most ${c.n} list members", v)
            }
        is PropertyConstraint.MemberShape ->
            eachValue { v ->
                val members = data.expandDataList(v)
                if (members == null) {
                    add(ConstraintType.MEMBER_SHAPE, "Value is not a valid SHACL RDF list", v)
                } else {
                    var failing = false
                    var undecided: Pair<RdfTerm, RdfResource>? = null
                    for (m in members) {
                        when (conformance(m, c.nestedShape, ctx, state)) {
                            Conformance.FAILS -> { failing = true; if (!sink.exhaustive) break }
                            Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, m, c.nestedShape)) undecided = m to c.nestedShape
                            Conformance.CONFORMS -> Unit
                        }
                    }
                    val u = undecided
                    if (failing) {
                        add(ConstraintType.MEMBER_SHAPE, "sh:memberShape violated for list value", v)
                    } else if (u != null) {
                        sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.MEMBER_SHAPE, v, u.first, c.nestedShape)
                    }
                }
            }
        is PropertyConstraint.UniqueMembers ->
            if (c.enabled) {
                eachValue { v ->
                    val members = data.expandDataList(v)
                    if (members == null) {
                        add(ConstraintType.UNIQUE_MEMBERS, "Value is not a valid SHACL RDF list", v)
                    } else if (distinctShaclTerms(members).size != members.size) {
                        add(ConstraintType.UNIQUE_MEMBERS, "sh:uniqueMembers violated", v)
                    }
                }
            }
        is PropertyConstraint.SubsetOfPath -> {
            val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
            eachValue { v ->
                if (shaclRdfTermFingerprint(v) !in otherKeys) add(ConstraintType.SUBSET_OF, "sh:subsetOf violated: value not reachable via referenced path", v)
            }
        }
        is PropertyConstraint.SingleLine ->
            if (c.enabled) {
                eachValue { v ->
                    val lex = literalLexicalString(v)
                    if (lex != null && singleLineBreakRegex.containsMatchIn(lex)) add(ConstraintType.SINGLE_LINE, "sh:singleLine violated", v)
                }
            }
        is PropertyConstraint.SomeValue -> {
            var matched = false
            var undecided: Pair<RdfTerm, RdfResource>? = null
            for (v in values) {
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.CONFORMS -> { matched = true; if (!sink.exhaustive) break }
                    Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, v, c.nestedShape)) undecided = v to c.nestedShape
                    Conformance.FAILS -> Unit
                }
            }
            val u = undecided
            if (!matched) {
                if (u != null) {
                    sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.SOME_VALUE, null, u.first, c.nestedShape)
                } else {
                    add(ConstraintType.SOME_VALUE, "sh:someValue requires at least one conforming value")
                }
            }
        }
        is PropertyConstraint.RootClass ->
            eachValue { v ->
                val cls = v as? Iri
                if (cls == null) {
                    add(ConstraintType.ROOT_CLASS, "sh:rootClass expects an IRI class value", v)
                } else if (c.roots.none { it in data.superclassCone(cls) }) {
                    add(ConstraintType.ROOT_CLASS, "sh:rootClass violated for $cls", v)
                }
            }
        is PropertyConstraint.Shape ->
            eachValue { v ->
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.FAILS -> add(ConstraintType.SHAPE, "sh:shape constraint failed for ${c.nestedShape.displayId()}", v)
                    Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.SHAPE, v, v, c.nestedShape)
                    Conformance.CONFORMS -> Unit
                }
            }
        is PropertyConstraint.Node ->
            eachValue { v ->
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.FAILS -> add(ConstraintType.NODE, "sh:node constraint failed for ${c.nestedShape.displayId()}", v)
                    Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE, v, v, c.nestedShape)
                    Conformance.CONFORMS -> Unit
                }
            }
        is PropertyConstraint.NodeByExpression ->
            eachValue { v ->
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.FAILS ->
                        add(ConstraintType.NODE_BY_EXPRESSION, "sh:nodeByExpression constraint failed for ${c.nestedShape.displayId()}", v, sourceConstraint = c.nestedShape)
                    Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE_BY_EXPRESSION, v, v, c.nestedShape)
                    Conformance.CONFORMS -> Unit
                }
            }
        is PropertyConstraint.Sparql -> sink.failAll(evaluateSparql(focus, tpl, c, ctx, currentShape, path, firstOnly = !sink.reports))
        is PropertyConstraint.ReifierShape,
        is PropertyConstraint.ReificationRequired,
        -> Unit
    }
}
