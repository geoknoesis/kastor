package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF

/**
 * SPARQL renderer that converts AST nodes to SPARQL query strings.
 *
 * The renderer produces SPARQL 1.1/1.2 syntax. Every caller-supplied value
 * (literals, IRIs, variable names, aliases, prefixes, function names, language
 * tags) is escaped or validated against the SPARQL grammar, so no input can
 * change the structure of the rendered query. Constructs that have no valid
 * SPARQL rendering are rejected with [IllegalArgumentException] instead of
 * being silently dropped or emitted as invalid text.
 */
object SparqlRenderer {

    fun render(query: SparqlQueryAst): String = buildString {
        BlankNodeScopes().checkQuery(query)
        when (query) {
            is SelectQueryAst -> renderSelect(query, subQuery = false)
            is AskQueryAst -> renderAsk(query)
            is ConstructQueryAst -> renderConstruct(query)
            is DescribeQueryAst -> renderDescribe(query)
        }
    }

    fun render(update: UpdateRequestAst): String = buildString {
        BlankNodeScopes().checkUpdate(update)
        renderPrologue(update.version, update.prefixes)
        append(update.operations.joinToString(" ;\n") { renderUpdateOperation(it) })
        if (update.operations.isNotEmpty()) append("\n")
    }

    // ============================================================================
    // QUERY FORMS
    // ============================================================================

    private fun StringBuilder.renderSelect(query: SelectQueryAst, subQuery: Boolean) {
        if (subQuery) {
            require(query.version == null && query.prefixes.isEmpty() && query.from.isEmpty() && query.fromNamed.isEmpty()) {
                "A sub-select cannot declare VERSION, PREFIX, FROM or FROM NAMED; declare them on the outer query"
            }
        } else {
            renderPrologue(query.version, query.prefixes)
        }
        require(!(query.distinct && query.reduced)) { "SELECT cannot be both DISTINCT and REDUCED" }

        append("SELECT")
        if (query.distinct) append(" DISTINCT")
        if (query.reduced) append(" REDUCED")
        append(" ")
        if (query.selectItems.isEmpty()) {
            append("*")
        } else {
            append(query.selectItems.joinToString(" ") { renderSelectItem(it) })
        }
        append("\n")
        if (!subQuery) renderFromClauses(query.from, query.fromNamed)

        append("WHERE ")
        append(renderGroup(query.where ?: EMPTY_GROUP, 0))
        append("\n")

        if (query.groupBy.isNotEmpty()) {
            append("GROUP BY ")
            append(query.groupBy.joinToString(" ") { SparqlSyntax.variable(it) })
            append("\n")
        }
        if (query.having.isNotEmpty()) {
            append("HAVING ")
            append(query.having.joinToString(" ") { "(${renderExpression(it)})" })
            append("\n")
        }
        if (query.orderBy.isNotEmpty()) {
            append("ORDER BY ")
            append(query.orderBy.joinToString(" ") { renderOrderClause(it) })
            append("\n")
        }
        query.limit?.let { require(it >= 0) { "LIMIT must not be negative: $it" }; append("LIMIT $it\n") }
        query.offset?.let { require(it >= 0) { "OFFSET must not be negative: $it" }; append("OFFSET $it\n") }
    }

    private fun StringBuilder.renderAsk(query: AskQueryAst) {
        renderPrologue(query.version, query.prefixes)
        append("ASK\n")
        renderFromClauses(query.from, query.fromNamed)
        append("WHERE ")
        append(renderGroup(query.where ?: EMPTY_GROUP, 0))
        append("\n")
    }

    private fun StringBuilder.renderConstruct(query: ConstructQueryAst) {
        renderPrologue(query.version, query.prefixes)
        append("CONSTRUCT {\n")
        query.template.forEach { triple ->
            append(INDENT)
            append(renderTemplateTriple(triple, allowVariables = true, allowBlankNodes = true, context = "CONSTRUCT template"))
            append(" .\n")
        }
        append("}\n")
        renderFromClauses(query.from, query.fromNamed)
        append("WHERE ")
        append(renderGroup(query.where ?: EMPTY_GROUP, 0))
        append("\n")
    }

    private fun StringBuilder.renderDescribe(query: DescribeQueryAst) {
        renderPrologue(query.version, query.prefixes)
        append("DESCRIBE ")
        if (query.describeTerms.isEmpty()) {
            append("*")
        } else {
            append(query.describeTerms.joinToString(" ") { term ->
                require(term is Var || term is Iri) { "DESCRIBE accepts only variables and IRIs, got ${term::class.simpleName}" }
                renderTerm(term)
            })
        }
        append("\n")
        renderFromClauses(query.from, query.fromNamed)
        query.where?.let {
            append("WHERE ")
            append(renderGroup(it, 0))
            append("\n")
        }
    }

    // ============================================================================
    // SELECT ITEMS
    // ============================================================================

    private fun renderSelectItem(item: SelectItemAst): String = when (item) {
        is VariableSelectItemAst -> SparqlSyntax.variable(item.variable)
        is AliasedSelectItemAst -> "(${renderExpression(item.expression)} AS ?${SparqlSyntax.varName(item.alias)})"
        is WildcardSelectItemAst -> "*"
    }

    // ============================================================================
    // GRAPH PATTERNS
    // ============================================================================

    /** Render [pattern] as a `GroupGraphPattern` (`{ ... }`), wrapping non-group patterns. */
    private fun renderGroup(pattern: GraphPatternAst, depth: Int): String {
        val elements = if (pattern is GroupPatternAst) pattern.patterns else listOf(pattern)
        if (elements.isEmpty()) return "{}"
        val inner = INDENT.repeat(depth + 1)
        return buildString {
            append("{\n")
            elements.forEach { element ->
                append(inner)
                append(renderElement(element, depth + 1))
                append("\n")
            }
            append(INDENT.repeat(depth))
            append("}")
        }
    }

    @Suppress("DEPRECATION") // QuotedTriplePatternAst / RdfStarTriplePatternAst are kept for back-compat
    private fun renderElement(pattern: GraphPatternAst, depth: Int): String = when (pattern) {
        is TriplePatternAst -> "${renderTriplePattern(pattern.subject, pattern.predicate, pattern.obj)} ."
        is GroupPatternAst -> renderGroup(pattern, depth)
        is OptionalPatternAst -> "OPTIONAL ${renderGroup(pattern.pattern, depth)}"
        is UnionPatternAst -> unionOperands(pattern).joinToString(" UNION ") { renderGroup(it, depth) }
        is MinusPatternAst -> {
            val minus = "MINUS ${renderGroup(pattern.right, depth)}"
            val left = pattern.left
            if (left is GroupPatternAst && left.patterns.isEmpty()) {
                minus
            } else {
                // `{ left MINUS { right } }`: rendered inline, MINUS would also subtract from every
                // pattern that precedes it in the enclosing group.
                val leftElements = if (left is GroupPatternAst) left.patterns else listOf(left)
                renderGroup(GroupPatternAst(leftElements + MinusPatternAst(EMPTY_GROUP, pattern.right)), depth)
            }
        }
        is GraphPatternAstImpl -> "GRAPH ${renderGraphName(pattern.graphName, "GRAPH")} ${renderGroup(pattern.pattern, depth)}"
        is ServicePatternAst -> "SERVICE ${renderGraphName(pattern.endpoint, "SERVICE")} ${renderGroup(pattern.pattern, depth)}"
        is ValuesPatternAst -> renderValues(pattern, depth)
        is PropertyPathPatternAst -> {
            requireSubject(pattern.subject)
            "${renderTerm(pattern.subject)} ${renderPath(pattern.path, PathPrecedence.ALTERNATIVE)} ${renderTerm(pattern.obj)} ."
        }
        is TripleTermPatternAst -> throw IllegalArgumentException(
            "A triple term <<( s p o )>> cannot be used as a standalone graph pattern; " +
                "use TripleTermObjectPatternAst or ReifierPatternAst to place it in object position"
        )
        is TripleTermObjectPatternAst ->
            "${renderTriplePattern(pattern.subject, pattern.predicate, null)} ${renderTripleTerm(pattern.tripleTerm)} ."
        is ReifierPatternAst -> {
            require(pattern.reifier is Var || pattern.reifier is Iri || pattern.reifier is BlankNode) {
                "A reifier must be a variable, IRI or blank node"
            }
            "${renderTerm(pattern.reifier)} ${SparqlSyntax.iriRef(RDF.reifies.value)} ${renderTripleTerm(pattern.tripleTerm)} ."
        }
        // SPARQL 1.2 reified-triple block: matches any reifier of the triple (the triple itself is not asserted).
        is QuotedTriplePatternAst -> "${renderReifiedTriple(pattern.subject, pattern.predicate, pattern.obj)} ."
        is RdfStarTriplePatternAst -> {
            val quoted = pattern.quotedTriple
            val reified = renderReifiedTriple(quoted.subject, quoted.predicate, quoted.obj)
            "$reified ${renderPredicate(pattern.predicate)} ${renderTerm(pattern.obj)} ."
        }
        is BindPatternAst -> "BIND(${renderExpression(pattern.expression)} AS ${SparqlSyntax.variable(pattern.variable)})"
        is FilterPatternAst -> "FILTER(${renderExpression(pattern.expression)})"
        is SubSelectPatternAst -> buildString {
            append("{\n")
            renderSelect(pattern.query, subQuery = true)
            append(INDENT.repeat(depth))
            append("}")
        }
    }

    private fun unionOperands(pattern: GraphPatternAst): List<GraphPatternAst> =
        if (pattern is UnionPatternAst) unionOperands(pattern.left) + unionOperands(pattern.right) else listOf(pattern)

    private fun renderValues(pattern: ValuesPatternAst, depth: Int): String = buildString {
        val single = pattern.variables.size == 1
        append("VALUES ")
        if (single) {
            append(SparqlSyntax.variable(pattern.variables.first()))
        } else {
            append("(")
            append(pattern.variables.joinToString(" ") { SparqlSyntax.variable(it) })
            append(")")
        }
        append(" {\n")
        pattern.values.forEach { row ->
            require(row.size == pattern.variables.size) {
                "VALUES row has ${row.size} values but ${pattern.variables.size} variables are declared"
            }
            append(INDENT.repeat(depth + 1))
            val rendered = row.map { renderDataBlockValue(it) }
            append(if (single) rendered.first() else rendered.joinToString(" ", "(", ")"))
            append("\n")
        }
        append(INDENT.repeat(depth))
        append("}")
    }

    private fun renderDataBlockValue(term: RdfTerm?): String = when (term) {
        null -> "UNDEF"
        is Iri, is Literal -> renderTerm(term)
        is TripleTerm -> {
            require(!containsBlankNodeOrVariable(term)) {
                "A triple term in VALUES may not contain blank nodes or variables: blank nodes are not allowed in data blocks"
            }
            renderTerm(term)
        }
        is Var, is BlankNode -> throw IllegalArgumentException("VALUES may only contain IRIs, literals, triple terms or UNDEF")
    }

    private fun renderGraphName(term: RdfTerm, keyword: String): String {
        require(term is Iri || term is Var) { "$keyword name must be an IRI or variable, got ${term::class.simpleName}" }
        return renderTerm(term)
    }

    private fun requireSubject(term: RdfTerm) {
        require(term !is TripleTerm) {
            "A triple term cannot be used in subject position (RDF 1.2); use a reifier instead"
        }
    }

    private fun renderPredicate(term: RdfTerm): String {
        require(term is Iri || term is Var) { "Predicate must be an IRI or variable, got ${term::class.simpleName}" }
        return renderTerm(term)
    }

    /** Render `s p o` (without the trailing dot). A null [obj] renders only `s p`. */
    private fun renderTriplePattern(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm?): String {
        requireSubject(subject)
        val sp = "${renderTerm(subject)} ${renderPredicate(predicate)}"
        return if (obj == null) sp else "$sp ${renderTerm(obj)}"
    }

    private fun renderReifiedTriple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm): String =
        "<< ${renderTriplePattern(subject, predicate, obj)} >>"

    private fun renderTripleTerm(term: TripleTermPatternAst): String =
        "<<( ${renderTriplePattern(term.subject, term.predicate, term.obj)} )>>"

    // ============================================================================
    // PROPERTY PATHS
    // ============================================================================

    /** Binding strength of path operators, from loosest to tightest (SPARQL 1.1 §18.4 grammar rules 88-95). */
    private enum class PathPrecedence { ALTERNATIVE, SEQUENCE, INVERSE, MODIFIED, PRIMARY }

    private fun renderPath(path: PropertyPathAst, required: PathPrecedence): String {
        val (text, precedence) = renderPathWithPrecedence(path)
        return if (precedence < required) "($text)" else text
    }

    private fun renderPathWithPrecedence(path: PropertyPathAst): Pair<String, PathPrecedence> = when (path) {
        is BasicPathAst -> {
            require(path.term is Iri) { "Property path steps must be IRIs, got ${path.term::class.simpleName}" }
            renderTerm(path.term) to PathPrecedence.PRIMARY
        }
        is OneOrMorePathAst -> "${renderPath(path.path, PathPrecedence.PRIMARY)}+" to PathPrecedence.MODIFIED
        is ZeroOrMorePathAst -> "${renderPath(path.path, PathPrecedence.PRIMARY)}*" to PathPrecedence.MODIFIED
        is ZeroOrOnePathAst -> "${renderPath(path.path, PathPrecedence.PRIMARY)}?" to PathPrecedence.MODIFIED
        is InversePathAst -> "^${renderPath(path.path, PathPrecedence.MODIFIED)}" to PathPrecedence.INVERSE
        is SequencePathAst ->
            "${renderPath(path.left, PathPrecedence.SEQUENCE)}/${renderPath(path.right, PathPrecedence.SEQUENCE)}" to PathPrecedence.SEQUENCE
        is AlternativePathAst ->
            "${renderPath(path.left, PathPrecedence.ALTERNATIVE)}|${renderPath(path.right, PathPrecedence.ALTERNATIVE)}" to PathPrecedence.ALTERNATIVE
        is NegationPathAst -> renderNegatedPropertySet(path.path) to PathPrecedence.PRIMARY
        is RangePathAst -> throw IllegalArgumentException(
            "Bounded path repetition {n,m} is not part of SPARQL 1.1/1.2; " +
                "expand it into an explicit sequence/alternative of paths"
        )
    }

    /** `!iri`, `!^iri` or `!(iri|^iri|...)` — the only forms the grammar allows after `!`. */
    private fun renderNegatedPropertySet(path: PropertyPathAst): String {
        val members = alternativeMembers(path).map { member ->
            when {
                member is BasicPathAst && member.term is Iri -> renderTerm(member.term)
                member is InversePathAst && member.path is BasicPathAst && (member.path as BasicPathAst).term is Iri ->
                    "^${renderTerm((member.path as BasicPathAst).term)}"
                else -> throw IllegalArgumentException(
                    "A negated property set may only contain IRIs and inverse IRIs (e.g. !(a|^b))"
                )
            }
        }
        return if (members.size == 1) "!${members.first()}" else "!(${members.joinToString("|")})"
    }

    private fun alternativeMembers(path: PropertyPathAst): List<PropertyPathAst> =
        if (path is AlternativePathAst) alternativeMembers(path.left) + alternativeMembers(path.right) else listOf(path)

    // ============================================================================
    // EXPRESSIONS
    // ============================================================================

    private fun renderExpression(expr: ExpressionAst): String = when (expr) {
        is TermExpressionAst -> {
            require(!containsBlankNode(expr.term)) {
                "Blank nodes cannot be used in SPARQL expressions (FILTER, BIND, SELECT, ORDER BY, HAVING); " +
                    "use a variable or the BNODE() function instead"
            }
            renderTerm(expr.term)
        }
        is ComparisonExpressionAst ->
            "${renderComparisonOperand(expr.left)} ${expr.operator.symbol} ${renderComparisonOperand(expr.right)}"
        is AndExpressionAst -> "(${renderExpression(expr.left)} && ${renderExpression(expr.right)})"
        is OrExpressionAst -> "(${renderExpression(expr.left)} || ${renderExpression(expr.right)})"
        is NotExpressionAst -> "!(${renderExpression(expr.expression)})"
        is FunctionCallAst ->
            "${SparqlSyntax.functionName(expr.name)}(${expr.arguments.joinToString(", ") { renderExpression(it) }})"
        is ConditionalExpressionAst ->
            "IF(${renderExpression(expr.condition)}, ${renderExpression(expr.thenValue)}, ${renderExpression(expr.elseValue)})"
        is AggregateExpressionAst -> renderAggregate(expr)
        is ArithmeticExpressionAst ->
            "(${renderExpression(expr.left)} ${expr.operator.symbol} ${renderExpression(expr.right)})"
    }

    /** Relational expressions are not associative in the grammar, so nested comparisons need brackets. */
    private fun renderComparisonOperand(expr: ExpressionAst): String =
        if (expr is ComparisonExpressionAst) "(${renderExpression(expr)})" else renderExpression(expr)

    private fun renderAggregate(expr: AggregateExpressionAst): String = buildString {
        append(expr.function.functionName)
        append("(")
        if (expr.distinct) append("DISTINCT ")
        val argument = expr.expression
        if (argument == null) {
            require(expr.function == AggregateFunction.COUNT) { "Only COUNT accepts '*'" }
            append("*")
        } else {
            append(renderExpression(argument))
        }
        expr.separator?.let {
            require(expr.function == AggregateFunction.GROUP_CONCAT) { "SEPARATOR is only valid for GROUP_CONCAT" }
            append(" ; SEPARATOR=")
            append(SparqlSyntax.quoted(it))
        }
        append(")")
    }

    // ============================================================================
    // ORDER BY
    // ============================================================================

    private fun renderOrderClause(clause: OrderClauseAst): String {
        val expr = renderExpression(clause.expression)
        // SPARQL 1.1 grammar (OrderCondition):
        //   ( ('ASC' | 'DESC') BrackettedExpression ) | ( Constraint | Var )
        // Ascending is the default, so a bare variable or call is emitted for ASC
        // (`ORDER BY ?x`); anything else, and DESC, must be bracketed.
        return when (clause.direction) {
            OrderDirection.ASC -> {
                val bare = (clause.expression as? TermExpressionAst)?.term is Var ||
                    clause.expression is FunctionCallAst || clause.expression is AggregateExpressionAst ||
                    expr.startsWith("(")
                if (bare) expr else "ASC($expr)"
            }
            OrderDirection.DESC -> "DESC($expr)"
        }
    }

    // ============================================================================
    // UPDATE OPERATIONS
    // ============================================================================

    private fun renderUpdateOperation(op: UpdateOperationAst): String = buildString {
        if (op !is ModifyOperationAst) {
            require(op.using.isEmpty() && op.usingNamed.isEmpty() && op.with == null) {
                "USING/USING NAMED/WITH are only valid on DELETE/INSERT ... WHERE operations"
            }
        }
        when (op) {
            is InsertDataOperationAst -> {
                append("INSERT DATA ")
                append(renderQuads(op.data, op.graphData, allowVariables = false, allowBlankNodes = true, context = "INSERT DATA"))
            }
            is DeleteDataOperationAst -> {
                append("DELETE DATA ")
                append(renderQuads(op.data, op.graphData, allowVariables = false, allowBlankNodes = false, context = "DELETE DATA"))
            }
            is ModifyOperationAst -> {
                require(op.delete.isNotEmpty() || op.insert.isNotEmpty() || op.deleteGraphs.isNotEmpty() || op.insertGraphs.isNotEmpty()) {
                    "A DELETE/INSERT operation needs a non-empty DELETE or INSERT template"
                }
                op.with?.let { append("WITH ${renderTerm(it)}\n") }
                if (op.delete.isNotEmpty() || op.deleteGraphs.isNotEmpty()) {
                    append("DELETE ")
                    append(renderQuads(op.delete, op.deleteGraphs, allowVariables = true, allowBlankNodes = false, context = "DELETE template"))
                    append("\n")
                }
                if (op.insert.isNotEmpty() || op.insertGraphs.isNotEmpty()) {
                    append("INSERT ")
                    append(renderQuads(op.insert, op.insertGraphs, allowVariables = true, allowBlankNodes = true, context = "INSERT template"))
                    append("\n")
                }
                op.using.forEach { append("USING ${renderTerm(it)}\n") }
                op.usingNamed.forEach { append("USING NAMED ${renderTerm(it)}\n") }
                append("WHERE ")
                append(renderGroup(op.where ?: EMPTY_GROUP, 0))
            }
            is DeleteWhereOperationAst -> {
                val (triples, graphs) = splitQuadPattern(op.where, "DELETE WHERE")
                append("DELETE WHERE ")
                append(renderQuads(triples, graphs, allowVariables = true, allowBlankNodes = false, context = "DELETE WHERE"))
            }
            is LoadOperationAst -> {
                append("LOAD")
                if (op.silent) append(" SILENT")
                append(" ${renderTerm(op.source)}")
                op.into?.let { append(" INTO GRAPH ${renderTerm(it)}") }
            }
            is ClearOperationAst -> {
                append("CLEAR")
                if (op.silent) append(" SILENT")
                append(op.graph?.let { " GRAPH ${renderTerm(it)}" } ?: " ${op.scope.keyword}")
            }
            is CreateOperationAst -> {
                append("CREATE")
                if (op.silent) append(" SILENT")
                append(" GRAPH ${renderTerm(op.graph)}")
            }
            is DropOperationAst -> {
                append("DROP")
                if (op.silent) append(" SILENT")
                append(op.graph?.let { " GRAPH ${renderTerm(it)}" } ?: " ${op.scope.keyword}")
            }
            is CopyOperationAst -> {
                append("COPY")
                if (op.silent) append(" SILENT")
                append(" ${graphOrDefault(op.source)} TO ${graphOrDefault(op.destination)}")
            }
            is MoveOperationAst -> {
                append("MOVE")
                if (op.silent) append(" SILENT")
                append(" ${graphOrDefault(op.source)} TO ${graphOrDefault(op.destination)}")
            }
            is AddOperationAst -> {
                append("ADD")
                if (op.silent) append(" SILENT")
                append(" ${graphOrDefault(op.source)} TO ${graphOrDefault(op.destination)}")
            }
        }
    }

    /** Split a DELETE WHERE pattern into default-graph triples and GRAPH blocks; anything else is rejected. */
    private fun splitQuadPattern(pattern: GraphPatternAst, context: String): Pair<List<TriplePatternAst>, List<QuadBlockAst>> {
        val triples = mutableListOf<TriplePatternAst>()
        val graphs = mutableListOf<QuadBlockAst>()
        fun visit(p: GraphPatternAst) {
            when (p) {
                is TriplePatternAst -> triples.add(p)
                is GroupPatternAst -> p.patterns.forEach(::visit)
                is GraphPatternAstImpl -> graphs.add(QuadBlockAst(p.graphName, collectTriples(p.pattern, context)))
                else -> throw IllegalArgumentException("$context may only contain triple patterns and GRAPH blocks, got ${p::class.simpleName}")
            }
        }
        visit(pattern)
        return triples to graphs
    }

    private fun collectTriples(pattern: GraphPatternAst, context: String): List<TriplePatternAst> = when (pattern) {
        is TriplePatternAst -> listOf(pattern)
        is GroupPatternAst -> pattern.patterns.flatMap { collectTriples(it, context) }
        else -> throw IllegalArgumentException("$context GRAPH blocks may only contain triple patterns, got ${pattern::class.simpleName}")
    }

    private fun renderQuads(
        triples: List<TriplePatternAst>,
        graphs: List<QuadBlockAst>,
        allowVariables: Boolean,
        allowBlankNodes: Boolean,
        context: String,
    ): String {
        if (triples.isEmpty() && graphs.isEmpty()) return "{}"
        return buildString {
            append("{\n")
            triples.forEach {
                append(INDENT)
                append(renderTemplateTriple(it, allowVariables, allowBlankNodes, context))
                append(" .\n")
            }
            graphs.forEach { block ->
                require(block.graph is Iri || (allowVariables && block.graph is Var)) {
                    "$context GRAPH name must be an IRI${if (allowVariables) " or variable" else ""}"
                }
                append(INDENT)
                append("GRAPH ${renderTerm(block.graph)} {\n")
                block.triples.forEach {
                    append(INDENT.repeat(2))
                    append(renderTemplateTriple(it, allowVariables, allowBlankNodes, context))
                    append(" .\n")
                }
                append(INDENT)
                append("}\n")
            }
            append("}")
        }
    }

    private fun renderTemplateTriple(
        triple: TriplePatternAst,
        allowVariables: Boolean,
        allowBlankNodes: Boolean,
        context: String,
    ): String {
        listOf(triple.subject, triple.predicate, triple.obj).forEach { term ->
            require(allowVariables || !containsVariable(term)) { "$context must not contain variables" }
            require(allowBlankNodes || !containsBlankNode(term)) { "$context must not contain blank nodes" }
        }
        return renderTriplePattern(triple.subject, triple.predicate, triple.obj)
    }

    private fun containsVariable(term: RdfTerm): Boolean = when (term) {
        is Var -> true
        is TripleTerm -> containsVariable(term.triple.obj)
        else -> false
    }

    private fun containsBlankNode(term: RdfTerm): Boolean = when (term) {
        is BlankNode -> true
        is TripleTerm -> term.triple.subject is BlankNode || containsBlankNode(term.triple.obj)
        else -> false
    }

    private fun containsBlankNodeOrVariable(term: RdfTerm): Boolean = containsBlankNode(term) || containsVariable(term)

    /** `GraphOrDefault`: `DEFAULT` for `null`, otherwise the graph IRI. */
    private fun graphOrDefault(graph: Iri?): String = graph?.let { renderTerm(it) } ?: "DEFAULT"

    /**
     * Enforces SPARQL 1.1 §4.1.4 / Update §3.1.1: a blank node label may not be used in two different
     * basic graph patterns of one query, nor in two operations of one update request. As in ARQ,
     * FILTER, BIND and VALUES do not end a basic graph pattern; any other non-triple element does.
     */
    private class BlankNodeScopes {
        private val owner = HashMap<String, Int>()
        private var nextScope = 0

        private fun newScope(): Int = nextScope++

        fun checkQuery(query: SparqlQueryAst) {
            query.whereClause()?.let { walk(it) }
        }

        fun checkUpdate(update: UpdateRequestAst) {
            update.operations.forEach { op ->
                val scope = newScope()
                when (op) {
                    is InsertDataOperationAst -> (op.data + op.graphData.flatMap { it.triples }).forEach { record(it, scope) }
                    is ModifyOperationAst -> {
                        (op.insert + op.insertGraphs.flatMap { it.triples }).forEach { record(it, scope) }
                        op.where?.let { walk(it) }
                    }
                    else -> Unit
                }
            }
        }

        private fun SparqlQueryAst.whereClause(): GraphPatternAst? = when (this) {
            is SelectQueryAst -> where
            is AskQueryAst -> where
            is ConstructQueryAst -> where
            is DescribeQueryAst -> where
        }

        private fun walk(pattern: GraphPatternAst) {
            if (pattern is GroupPatternAst) walkGroup(pattern.patterns) else walkGroup(listOf(pattern))
        }

        @Suppress("DEPRECATION")
        private fun walkGroup(elements: List<GraphPatternAst>) {
            var scope = newScope()
            for (element in elements) {
                when (element) {
                    is TriplePatternAst -> record(element, scope)
                    is PropertyPathPatternAst -> { term(element.subject, scope); term(element.obj, scope) }
                    is TripleTermObjectPatternAst -> { term(element.subject, scope); tripleTerm(element.tripleTerm, scope) }
                    is ReifierPatternAst -> { term(element.reifier, scope); tripleTerm(element.tripleTerm, scope) }
                    is QuotedTriplePatternAst -> { term(element.subject, scope); term(element.obj, scope) }
                    is RdfStarTriplePatternAst -> {
                        term(element.quotedTriple.subject, scope); term(element.quotedTriple.obj, scope); term(element.obj, scope)
                    }
                    is TripleTermPatternAst -> tripleTerm(element, scope)
                    is FilterPatternAst, is BindPatternAst, is ValuesPatternAst -> Unit
                    else -> {
                        nested(element)
                        scope = newScope()
                    }
                }
            }
        }

        private fun nested(element: GraphPatternAst) {
            when (element) {
                is GroupPatternAst -> walkGroup(element.patterns)
                is OptionalPatternAst -> walk(element.pattern)
                is UnionPatternAst -> { walk(element.left); walk(element.right) }
                is MinusPatternAst -> { walk(element.left); walk(element.right) }
                is GraphPatternAstImpl -> walk(element.pattern)
                is ServicePatternAst -> walk(element.pattern)
                is SubSelectPatternAst -> element.query.where?.let { walk(it) }
                else -> Unit
            }
        }

        private fun record(triple: TriplePatternAst, scope: Int) {
            term(triple.subject, scope)
            term(triple.obj, scope)
        }

        private fun tripleTerm(t: TripleTermPatternAst, scope: Int) {
            term(t.subject, scope)
            term(t.obj, scope)
        }

        private fun term(term: RdfTerm, scope: Int) {
            when (term) {
                is BlankNode -> {
                    val label = term.id.removePrefix("_:")
                    val first = owner.getOrPut(label) { scope }
                    require(first == scope) {
                        "Blank node label '_:$label' is used in more than one basic graph pattern (or update operation); " +
                            "SPARQL scopes blank node labels to a single basic graph pattern. Use a variable to join across patterns"
                    }
                }
                is TripleTerm -> { term(term.triple.subject, scope); term(term.triple.obj, scope) }
                else -> Unit
            }
        }
    }

    // ============================================================================
    // HELPERS
    // ============================================================================

    private fun StringBuilder.renderPrologue(version: String?, prefixes: List<PrefixDeclaration>) {
        version?.let { append(SparqlSyntax.versionDecl(it)).append("\n") }
        prefixes.forEach { append(SparqlSyntax.prefixDecl(it)).append("\n") }
        if (version != null || prefixes.isNotEmpty()) append("\n")
    }

    private fun StringBuilder.renderFromClauses(from: List<Iri>, fromNamed: List<Iri>) {
        from.forEach { append("FROM ${renderTerm(it)}\n") }
        fromNamed.forEach { append("FROM NAMED ${renderTerm(it)}\n") }
    }

    private fun renderTerm(term: RdfTerm): String = when (term) {
        is Var -> SparqlSyntax.variable(term)
        is Iri -> SparqlSyntax.iriRef(term.value)
        is BlankNode -> SparqlSyntax.blankNode(term.id)
        is Literal -> SparqlSyntax.literal(term)
        is TripleTerm -> {
            val t = term.triple
            "<<( ${renderTerm(t.subject)} ${renderTerm(t.predicate)} ${renderTerm(t.obj)} )>>"
        }
    }

    private const val INDENT = "  "
    private val EMPTY_GROUP = GroupPatternAst(emptyList())
}
