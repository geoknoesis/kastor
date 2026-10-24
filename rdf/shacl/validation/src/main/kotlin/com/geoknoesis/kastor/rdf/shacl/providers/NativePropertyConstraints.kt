package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
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
    when (c) {
        is PropertyConstraint.Datatype,
        is PropertyConstraint.Class,
        is PropertyConstraint.ClassAnyOf,
        is PropertyConstraint.NodeKind,
        is PropertyConstraint.RootClass ->
            evaluateTermType(focus, tpl, values, c, ctx, sink)
        is PropertyConstraint.Pattern,
        is PropertyConstraint.MinLength,
        is PropertyConstraint.MaxLength,
        is PropertyConstraint.LanguageIn,
        is PropertyConstraint.UniqueLang,
        is PropertyConstraint.SingleLine ->
            evaluateText(focus, tpl, values, c, ctx, currentShape, sink)
        is PropertyConstraint.MinCount,
        is PropertyConstraint.MaxCount,
        is PropertyConstraint.In,
        is PropertyConstraint.HasValue ->
            evaluateValueSet(focus, tpl, values, c, sink)
        is PropertyConstraint.EqualsPath,
        is PropertyConstraint.DisjointPath,
        is PropertyConstraint.LessThanPath,
        is PropertyConstraint.LessThanOrEqualsPath,
        is PropertyConstraint.SubsetOfPath ->
            evaluatePathComparison(focus, tpl, values, c, ctx, sink)
        is PropertyConstraint.MinInclusive,
        is PropertyConstraint.MaxInclusive,
        is PropertyConstraint.MinExclusive,
        is PropertyConstraint.MaxExclusive ->
            evaluateBound(focus, tpl, values, c, sink)
        is PropertyConstraint.MinListLength,
        is PropertyConstraint.MaxListLength,
        is PropertyConstraint.MemberShape,
        is PropertyConstraint.UniqueMembers ->
            evaluateList(focus, tpl, values, c, ctx, state, sink)
        is PropertyConstraint.SomeValue,
        is PropertyConstraint.Shape,
        is PropertyConstraint.Node,
        is PropertyConstraint.NodeByExpression ->
            evaluateNestedShape(focus, tpl, values, c, ctx, state, sink)
        is PropertyConstraint.Qualified ->
            evaluateQualified(focus, tpl, values, c, ctx, state, sink)
        is PropertyConstraint.Expression ->
            evaluateExpression(focus, tpl, values, c, ctx, state, sink)
        is PropertyConstraint.NodeByExpressionComputed ->
            evaluateComputedNodeByExpression(focus, tpl, values, c, ctx, state, sink)
        is PropertyConstraint.Sparql -> {
            // SHACL 1.2 SPARQL: a constraint deactivated for the focus node has no results.
            if (c.deactivatedWhen == null || !deactivates(c.deactivatedWhen, focus, ctx, state.depth)) {
                sink.failAll(evaluateSparql(focus, tpl, c, ctx, path, firstOnly = !sink.reports))
            }
        }
        is PropertyConstraint.ReifierShape,
        is PropertyConstraint.ReificationRequired,
        -> Unit
        is PropertyConstraint.Deactivatable ->
            when (c.constraint) {
                // Resolved per focus node, with the triples they constrain, by validatePropertyShape: evaluating the
                // conditions here too would evaluate them twice.
                is PropertyConstraint.ReifierShape, is PropertyConstraint.ReificationRequired -> Unit
                // SHACL 1.2 Core §3.1.6: a constraint a reifier deactivates for the focus node is ignored. A failure of
                // the expression is thrown; a provisional answer it reads, when no condition is true, makes the
                // constraint undefined for now (see evaluateConstraintsForValues).
                else -> if (!anyDeactivates(c.deactivatedWhen, focus, ctx, state.depth)) {
                    evaluateConstraint(focus, tpl, values, c.constraint, ctx, state, currentShape, path, sink)
                }
            }
        is PropertyConstraint.SparqlComponent ->
            sink.failAll(evaluateSparqlComponent(focus, tpl, values, c, ctx, path != null, firstOnly = !sink.reports))
    }
}

private fun NativeShaclValidator.evaluateTermType(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    ctx: NativeValidationContext,
    sink: Sink,
) {
    val data = ctx.data
    when (c) {
        is PropertyConstraint.Datatype ->
            eachValue(values, sink) { v ->
                if (!literalMatchesShaclDatatypes(v, c.allowed)) {
                    tpl.add(focus, sink,
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
            eachValue(values, sink) { v ->
                val ok = v is RdfResource && data.typesOf(v).any { t -> c.iri in data.superclassCone(t) }
                if (!ok) tpl.add(focus, sink, ConstraintType.CLASS, "Expected rdf:type (subclass of) ${c.iri.value} for value $v", v)
            }
        is PropertyConstraint.ClassAnyOf ->
            eachValue(values, sink) { v ->
                val ok = v is RdfResource && c.options.any { req -> data.typesOf(v).any { t -> req in data.superclassCone(t) } }
                if (!ok) tpl.add(focus, sink, ConstraintType.CLASS, "Expected rdf:type matching one of ${c.options.joinToString { it.value }} for value $v", v)
            }
        is PropertyConstraint.NodeKind ->
            eachValue(values, sink) { v ->
                if (!c.kinds.any { matchesNodeKind(v, it) }) tpl.add(focus, sink, ConstraintType.NODE_KIND, "Node kind ${c.kinds.joinToString { it.value }} required for $v", v)
            }
        is PropertyConstraint.RootClass ->
            eachValue(values, sink) { v ->
                val cls = v as? Iri
                if (cls == null) {
                    tpl.add(focus, sink, ConstraintType.ROOT_CLASS, "sh:rootClass expects an IRI class value", v)
                } else if (c.roots.none { it in data.superclassCone(cls) }) {
                    tpl.add(focus, sink, ConstraintType.ROOT_CLASS, "sh:rootClass violated for $cls", v)
                }
            }
        else -> error("Unexpected TermType constraint: $c")
    }
}

private fun NativeShaclValidator.evaluateText(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    ctx: NativeValidationContext,
    currentShape: RdfResource,
    sink: Sink,
) {
    when (c) {
        is PropertyConstraint.Pattern ->
            eachValue(values, sink) { v ->
                val lex = literalLexicalString(v)
                val outcome = if (lex == null) PatternOutcome.NO_MATCH else matchPattern(c, lex, currentShape, ctx)
                when {
                    outcome.undecided != null -> sink.undecidedPattern(focus, tpl, v, outcome.undecided)
                    !outcome.matches -> tpl.add(focus, sink, ConstraintType.PATTERN, "Pattern ${c.pattern} violated for value $v", v)
                }
            }
        is PropertyConstraint.MinLength ->
            eachValue(values, sink) { v ->
                val lex = literalLexicalString(v)
                if (lex == null || lex.codePointCount(0, lex.length) < c.n) tpl.add(focus, sink, ConstraintType.MIN_LENGTH, "minLength ${c.n} violated for $v", v)
            }
        is PropertyConstraint.MaxLength ->
            eachValue(values, sink) { v ->
                val lex = literalLexicalString(v)
                if (lex == null || lex.codePointCount(0, lex.length) > c.n) tpl.add(focus, sink, ConstraintType.MAX_LENGTH, "maxLength ${c.n} violated for $v", v)
            }
        is PropertyConstraint.LanguageIn ->
            eachValue(values, sink) { v ->
                val lang = (v as? LangString)?.lang?.takeIf { it.isNotEmpty() }
                val ok = lang != null && c.langs.any { allowed -> languageTagMatchesLanguageRange(lang, allowed) }
                if (!ok) tpl.add(focus, sink, ConstraintType.LANGUAGE_IN, "languageIn violated for $v", v)
            }
        is PropertyConstraint.UniqueLang ->
            if (c.enabled) {
                // Language tags are compared case-insensitively.
                val duplicated = values.mapNotNull { v -> (v as? LangString)?.let { "${it.lang.lowercase()}\u0000${it.direction?.token ?: ""}" } }
                    .groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                duplicated.forEach { _ -> tpl.add(focus, sink, ConstraintType.UNIQUE_LANG, "sh:uniqueLang violated") }
            }
        is PropertyConstraint.SingleLine ->
            if (c.enabled) {
                eachValue(values, sink) { v ->
                    val lex = literalLexicalString(v)
                    if (lex != null && singleLineBreakRegex.containsMatchIn(lex)) tpl.add(focus, sink, ConstraintType.SINGLE_LINE, "sh:singleLine violated", v)
                }
            }
        else -> error("Unexpected Text constraint: $c")
    }
}

private fun NativeShaclValidator.evaluateValueSet(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    sink: Sink,
) {
    when (c) {
        is PropertyConstraint.MinCount ->
            if (values.size < c.n) tpl.add(focus, sink, ConstraintType.MIN_COUNT, "Minimum cardinality ${c.n} required, found ${values.size}", params = mapOf("min" to c.n, "actual" to values.size))
        is PropertyConstraint.MaxCount ->
            if (values.size > c.n) tpl.add(focus, sink, ConstraintType.MAX_COUNT, "Maximum cardinality ${c.n} allowed, found ${values.size}", params = mapOf("max" to c.n, "actual" to values.size))
        is PropertyConstraint.In ->
            eachValue(values, sink) { v ->
                if (!c.allowed.any { shaclRdfTermEquals(it, v) }) tpl.add(focus, sink, ConstraintType.IN, "Value $v not in sh:in", v)
            }
        is PropertyConstraint.HasValue ->
            if (values.none { shaclRdfTermEquals(it, c.value) }) tpl.add(focus, sink, ConstraintType.HAS_VALUE, "sh:hasValue missing ${c.value}")
        else -> error("Unexpected ValueSet constraint: $c")
    }
}

private fun NativeShaclValidator.evaluatePathComparison(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    ctx: NativeValidationContext,
    sink: Sink,
) {
    val data = ctx.data
    when (c) {
        is PropertyConstraint.EqualsPath -> {
            val other = PathEvaluator.evaluate(focus, c.otherPath, data)
            val otherKeys = fingerprints(other)
            val valueKeys = fingerprints(values)
            values.filter { shaclRdfTermFingerprint(it) !in otherKeys }.forEach { tpl.add(focus, sink, ConstraintType.EQUALS, "sh:equals: value $it is missing from the referenced path", it) }
            other.filter { shaclRdfTermFingerprint(it) !in valueKeys }.forEach { tpl.add(focus, sink, ConstraintType.EQUALS, "sh:equals: referenced value $it is missing from the path", it) }
        }
        is PropertyConstraint.DisjointPath -> {
            val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
            values.filter { shaclRdfTermFingerprint(it) in otherKeys }.forEach { tpl.add(focus, sink, ConstraintType.DISJOINT, "sh:disjoint violated: $it is shared with the referenced path", it) }
        }
        // One result per (value, other value) pair that is not ordered; orderViolations sorts once when it can.
        is PropertyConstraint.LessThanPath ->
            for ((v, w) in orderViolations(values, PathEvaluator.evaluate(focus, c.otherPath, data), strict = true)) {
                tpl.add(focus, sink, ConstraintType.LESS_THAN, "sh:lessThan violated comparing $v and $w", v)
                if (sink.stop) break
            }
        is PropertyConstraint.LessThanOrEqualsPath ->
            for ((v, w) in orderViolations(values, PathEvaluator.evaluate(focus, c.otherPath, data), strict = false)) {
                tpl.add(focus, sink, ConstraintType.LESS_THAN_OR_EQUALS, "sh:lessThanOrEquals violated comparing $v and $w", v)
                if (sink.stop) break
            }
        is PropertyConstraint.SubsetOfPath -> {
            val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
            eachValue(values, sink) { v ->
                if (shaclRdfTermFingerprint(v) !in otherKeys) tpl.add(focus, sink, ConstraintType.SUBSET_OF, "sh:subsetOf violated: value not reachable via referenced path", v)
            }
        }
        else -> error("Unexpected PathComparison constraint: $c")
    }
}

private fun NativeShaclValidator.evaluateBound(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    sink: Sink,
) {
    when (c) {
        is PropertyConstraint.MinInclusive ->
            eachValue(values, sink) { v -> if (!satisfiesMinInclusive(v, c.bound)) tpl.add(focus, sink, ConstraintType.MIN_INCLUSIVE, "minInclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.MaxInclusive ->
            eachValue(values, sink) { v -> if (!satisfiesMaxInclusive(v, c.bound)) tpl.add(focus, sink, ConstraintType.MAX_INCLUSIVE, "maxInclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.MinExclusive ->
            eachValue(values, sink) { v -> if (!satisfiesMinExclusive(v, c.bound)) tpl.add(focus, sink, ConstraintType.MIN_EXCLUSIVE, "minExclusive violated for $v vs bound ${c.bound}", v) }
        is PropertyConstraint.MaxExclusive ->
            eachValue(values, sink) { v -> if (!satisfiesMaxExclusive(v, c.bound)) tpl.add(focus, sink, ConstraintType.MAX_EXCLUSIVE, "maxExclusive violated for $v vs bound ${c.bound}", v) }
        else -> error("Unexpected Bound constraint: $c")
    }
}

private fun NativeShaclValidator.evaluateList(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    val data = ctx.data
    when (c) {
        is PropertyConstraint.MinListLength ->
            eachValue(values, sink) { v ->
                val members = data.expandDataList(v)
                if (members == null || members.size < c.n) tpl.add(focus, sink, ConstraintType.MIN_LIST_LENGTH, "sh:minListLength requires at least ${c.n} list members", v)
            }
        is PropertyConstraint.MaxListLength ->
            eachValue(values, sink) { v ->
                val members = data.expandDataList(v)
                if (members == null || members.size > c.n) tpl.add(focus, sink, ConstraintType.MAX_LIST_LENGTH, "sh:maxListLength allows at most ${c.n} list members", v)
            }
        is PropertyConstraint.MemberShape ->
            eachValue(values, sink) { v ->
                val members = data.expandDataList(v)
                if (members == null) {
                    tpl.add(focus, sink, ConstraintType.MEMBER_SHAPE, "Value is not a valid SHACL RDF list", v)
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
                        tpl.add(focus, sink, ConstraintType.MEMBER_SHAPE, "sh:memberShape violated for list value", v)
                    } else if (u != null) {
                        sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.MEMBER_SHAPE, v, u.first, c.nestedShape)
                    }
                }
            }
        is PropertyConstraint.UniqueMembers ->
            if (c.enabled) {
                eachValue(values, sink) { v ->
                    val members = data.expandDataList(v)
                    if (members == null) {
                        tpl.add(focus, sink, ConstraintType.UNIQUE_MEMBERS, "Value is not a valid SHACL RDF list", v)
                    } else if (distinctShaclTerms(members).size != members.size) {
                        tpl.add(focus, sink, ConstraintType.UNIQUE_MEMBERS, "sh:uniqueMembers violated", v)
                    }
                }
            }
        else -> error("Unexpected List constraint: $c")
    }
}

private fun NativeShaclValidator.evaluateNestedShape(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    when (c) {
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
                    tpl.add(focus, sink, ConstraintType.SOME_VALUE, "sh:someValue requires at least one conforming value")
                }
            }
        }
        is PropertyConstraint.Shape ->
            eachValue(values, sink) { v ->
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.FAILS -> tpl.add(focus, sink, ConstraintType.SHAPE, "sh:shape constraint failed for ${c.nestedShape.displayId()}", v)
                    Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.SHAPE, v, v, c.nestedShape)
                    Conformance.CONFORMS -> Unit
                }
            }
        is PropertyConstraint.Node ->
            eachValue(values, sink) { v ->
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.FAILS -> tpl.add(focus, sink, ConstraintType.NODE, "sh:node constraint failed for ${c.nestedShape.displayId()}", v)
                    Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE, v, v, c.nestedShape)
                    Conformance.CONFORMS -> Unit
                }
            }
        is PropertyConstraint.NodeByExpression ->
            eachValue(values, sink) { v ->
                when (conformance(v, c.nestedShape, ctx, state)) {
                    Conformance.FAILS ->
                        tpl.add(focus, sink, ConstraintType.NODE_BY_EXPRESSION, "sh:nodeByExpression constraint failed for ${c.nestedShape.displayId()}", v, sourceConstraint = c.nestedShape)
                    Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE_BY_EXPRESSION, v, v, c.nestedShape)
                    Conformance.CONFORMS -> Unit
                }
            }
        else -> error("Unexpected NestedShape constraint: $c")
    }
}

private fun NativeShaclValidator.evaluateQualified(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint.Qualified,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
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
            tpl.add(focus, sink, ConstraintType.QUALIFIED_MIN_COUNT, "qualifiedMinCount violated (required ${c.min}, found $definite)", params = mapOf("min" to c.min, "actual" to definite))
        } else if (definite < c.min && u != null) {
            sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.QUALIFIED_MIN_COUNT, null, u.first, u.second)
        }
    }
    if (c.max != null) {
        if (definite > c.max) {
            tpl.add(focus, sink, ConstraintType.QUALIFIED_MAX_COUNT, "qualifiedMaxCount violated (max ${c.max}, found $definite)", params = mapOf("max" to c.max, "actual" to definite))
        } else if (possible > c.max && u != null) {
            sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.QUALIFIED_MAX_COUNT, null, u.first, u.second)
        }
    }
}

/**
 * `sh:expression` (Node Expressions §7.1): one result per value node `v` for which the expression, evaluated with the
 * focus node and the scope `{ "value": v }`, does not produce exactly the term `"true"^^xsd:boolean` (R5: not `"1"`,
 * and not several nodes). An evaluation failure is a validation failure ([evalNodeExpression]); a provisional answer
 * of the recursion solver ([ProvisionalUndefined]) is reported as undefined by [evaluateConstraintsForValues].
 */
private fun NativeShaclValidator.evaluateExpression(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint.Expression,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    eachValue(values, sink) { v ->
        val out = evalNodeExpression(c.expr, ctx, focus, mapOf("value" to v), shapeDepth = state.depth)
        if (out.size != 1 || TrueLiteral != out.single()) {
            val produced = if (out.size > 3) "${out.size} nodes" else out.joinToString(prefix = "( ", postfix = " )")
            tpl.add(focus, sink, ConstraintType.EXPRESSION, "sh:expression produced $produced instead of ( true ) for value $v", v, sourceConstraint = c.node)
        }
    }
}

/**
 * A computed `sh:nodeByExpression` (Node Expressions §7.2): each value node `v` must conform to every output node of
 * the expression evaluated with `v` as focus node. An output that is not a shape of the shapes graph, a candidate
 * shape that does not compile (R8), an evaluation failure, and a conformance check that fails or stays undefined are
 * validation failures ("A failure MUST be produced"); a non-conforming value node is a result whose
 * `sh:sourceConstraint` is the shape. Only a final undefined answer is a failure: a provisional one of the recursion
 * solver ([NativeValidationContext.isProvisional]) is reported as undefined ([Sink.undefinedAnswer]), and the solver
 * evaluates the question again.
 */
private fun NativeShaclValidator.evaluateComputedNodeByExpression(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint.NodeByExpressionComputed,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    eachValue(values, sink) { v ->
        for (output in evalNodeExpression(c.expr, ctx, v, shapeDepth = state.depth)) {
            if (sink.stop) return
            val shape = computedShape(output, ctx)
            when (conformance(v, shape, ctx, state)) {
                Conformance.FAILS ->
                    tpl.add(focus, sink, ConstraintType.NODE_BY_EXPRESSION, "sh:nodeByExpression constraint failed for ${shape.displayId()}", v, sourceConstraint = shape)
                Conformance.UNDEFINED -> {
                    if (!ctx.isProvisional(v, shape)) {
                        throw ShaclValidationException("sh:nodeByExpression: whether ${displayTerm(v)} conforms to ${shape.displayId()} cannot be decided")
                    }
                    sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE_BY_EXPRESSION, v, v, shape)
                }
                Conformance.CONFORMS -> Unit
            }
        }
    }
}

/** The output node [term] of a computed `sh:nodeByExpression` as a compiled shape, else a validation failure. */
private fun computedShape(term: RdfTerm, ctx: NativeValidationContext): RdfResource {
    val compiled = ctx.compiled
    compiled.candidateErrors[term]?.let { throw ShaclValidationException("sh:nodeByExpression: $it") }
    if (term !is RdfResource || (term !in compiled.referencedNodeShapes && term !in compiled.referencedPropertyShapes)) {
        throw ShaclValidationException("sh:nodeByExpression: the output node ${displayTerm(term)} is not a shape of the shapes graph")
    }
    return term
}

private fun ResultTemplate.add(
    focus: RdfTerm,
    sink: Sink,
    type: ConstraintType,
    message: String,
    value: RdfTerm? = null,
    params: Map<String, Any> = emptyMap(),
    severity: ViolationSeverity = this.severity,
    severityIri: Iri? = this.severityCustomIri,
    messages: List<Literal> = this.messages,
    sourceConstraint: RdfTerm? = null,
) {
    sink.failed = true
    if (sink.reports) {
        sink.results.add(violation(focus, this, constraintStub(type, path?.predicate, params), message, value, severity, severityIri, messages, sourceConstraint))
    }
}


/** Stops value iteration on the first definite failure in conformance mode. */
private inline fun eachValue(values: List<RdfTerm>, sink: Sink, action: (RdfTerm) -> Unit) {
    for (value in values) {
        if (sink.stop) return
        action(value)
    }
}

private fun fingerprints(terms: List<RdfTerm>): Set<String> = terms.mapTo(HashSet()) { shaclRdfTermFingerprint(it) }
