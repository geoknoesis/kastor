package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * SPARQL renderer that converts AST nodes to SPARQL query strings.
 * 
 * This renderer produces valid SPARQL 1.1/1.2 syntax with proper formatting.
 */
object SparqlRenderer {
    
    fun render(query: SparqlQueryAst): String = buildString {
        when (query) {
            is SelectQueryAst -> renderSelect(query)
            is AskQueryAst -> renderAsk(query)
            is ConstructQueryAst -> renderConstruct(query)
            is DescribeQueryAst -> renderDescribe(query)
        }
    }
    
    fun render(update: UpdateRequestAst): String = buildString {
        renderUpdate(update)
    }
    
    // ============================================================================
    // QUERY FORMS
    // ============================================================================
    
    private fun StringBuilder.renderSelect(query: SelectQueryAst) {
        renderVersionAndPrefixes(query.version, query.prefixes)
        renderFromClauses(query.from, query.fromNamed)
        
        append("SELECT")
        if (query.distinct) append(" DISTINCT")
        if (query.reduced) append(" REDUCED")
        append(" ")
        
        if (query.selectItems.isEmpty()) {
            append("*")
        } else {
            append(query.selectItems.joinToString(" ") { renderSelectItem(it) })
        }
        append("\n\n")
        
        query.where?.let {
            append("WHERE ")
            renderGraphPattern(it, this)
            append("\n")
        }
        
        if (query.groupBy.isNotEmpty()) {
            append("GROUP BY ")
            append(query.groupBy.joinToString(" ") { it.toString() })
            append("\n")
        }
        
        if (query.having.isNotEmpty()) {
            append("HAVING ")
            append(query.having.joinToString(" ") { "FILTER(${renderExpression(it)})" })
            append("\n")
        }
        
        if (query.orderBy.isNotEmpty()) {
            append("ORDER BY ")
            append(query.orderBy.joinToString(" ") { renderOrderClause(it) })
            append("\n")
        }
        
        query.limit?.let { append("LIMIT $it\n") }
        query.offset?.let { append("OFFSET $it\n") }
    }
    
    private fun StringBuilder.renderAsk(query: AskQueryAst) {
        renderVersionAndPrefixes(query.version, query.prefixes)
        renderFromClauses(query.from, query.fromNamed)
        append("ASK")
        query.where?.let {
            append(" ")
            renderGraphPattern(it, this)
        }
        append("\n")
    }
    
    private fun StringBuilder.renderConstruct(query: ConstructQueryAst) {
        renderVersionAndPrefixes(query.version, query.prefixes)
        renderFromClauses(query.from, query.fromNamed)
        append("CONSTRUCT {\n")
        query.template.forEach { triple ->
            append("  ")
            renderTriplePattern(triple, this)
            append("\n")
        }
        append("}\n")
        query.where?.let {
            append("WHERE ")
            renderGraphPattern(it, this)
            append("\n")
        }
    }
    
    private fun StringBuilder.renderDescribe(query: DescribeQueryAst) {
        renderVersionAndPrefixes(query.version, query.prefixes)
        renderFromClauses(query.from, query.fromNamed)
        append("DESCRIBE ")
        if (query.describeTerms.isEmpty()) {
            append("*")
        } else {
            append(query.describeTerms.joinToString(" ") { renderTerm(it) })
        }
        append("\n")
        query.where?.let {
            append("WHERE ")
            renderGraphPattern(it, this)
            append("\n")
        }
    }
    
    // ============================================================================
    // SELECT ITEMS
    // ============================================================================
    
    private fun renderSelectItem(item: SelectItemAst): String = when (item) {
        is VariableSelectItemAst -> item.variable.toString()
        is AliasedSelectItemAst -> "${renderExpression(item.expression)} AS ?${item.alias}"
        is WildcardSelectItemAst -> "*"
    }
    
    // ============================================================================
    // GRAPH PATTERNS
    // ============================================================================
    
    private fun renderGraphPattern(pattern: GraphPatternAst, sb: StringBuilder) {
        when (pattern) {
            is TriplePatternAst -> {
                renderTriplePattern(pattern, sb)
                sb.append(" .")
            }
            is GroupPatternAst -> {
                if (pattern.patterns.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append("{\n")
                    pattern.patterns.forEach { p ->
                        sb.append("  ")
                        renderGraphPattern(p, sb)
                        sb.append("\n")
                    }
                    sb.append("}")
                }
            }
            is OptionalPatternAst -> {
                sb.append("OPTIONAL ")
                renderGraphPattern(pattern.pattern, sb)
            }
            is UnionPatternAst -> {
                renderGraphPattern(pattern.left, sb)
                sb.append("\nUNION\n")
                renderGraphPattern(pattern.right, sb)
            }
            is MinusPatternAst -> {
                renderGraphPattern(pattern.left, sb)
                sb.append("\nMINUS ")
                renderGraphPattern(pattern.right, sb)
            }
            is GraphPatternAstImpl -> {
                sb.append("GRAPH ${renderTerm(pattern.graphName)} ")
                renderGraphPattern(pattern.pattern, sb)
            }
            is ServicePatternAst -> {
                sb.append("SERVICE ${renderTerm(pattern.endpoint)} ")
                renderGraphPattern(pattern.pattern, sb)
            }
            is ValuesPatternAst -> {
                sb.append("VALUES ")
                if (pattern.variables.size == 1) {
                    sb.append(pattern.variables.first())
                } else {
                    sb.append("(")
                    sb.append(pattern.variables.joinToString(" ") { it.toString() })
                    sb.append(")")
                }
                sb.append(" {\n")
                pattern.values.forEach { valueRow ->
                    sb.append("  ")
                    if (valueRow.size == 1) {
                        sb.append(renderTerm(valueRow.first()))
                    } else {
                        sb.append("(")
                        sb.append(valueRow.joinToString(" ") { renderTerm(it) })
                        sb.append(")")
                    }
                    sb.append("\n")
                }
                sb.append("}")
            }
            is PropertyPathPatternAst -> {
                sb.append(renderTerm(pattern.subject))
                sb.append(" ")
                sb.append(renderPropertyPath(pattern.path))
                sb.append(" ")
                sb.append(renderTerm(pattern.obj))
                sb.append(" .")
            }
            is QuotedTriplePatternAst -> {
                sb.append("<< ")
                sb.append(renderTerm(pattern.subject))
                sb.append(" ")
                sb.append(renderTerm(pattern.predicate))
                sb.append(" ")
                sb.append(renderTerm(pattern.obj))
                sb.append(" >>")
            }
            is RdfStarTriplePatternAst -> {
                renderGraphPattern(pattern.quotedTriple, sb)
                sb.append(" ")
                sb.append(renderTerm(pattern.predicate))
                sb.append(" ")
                sb.append(renderTerm(pattern.obj))
                sb.append(" .")
            }
            is BindPatternAst -> {
                sb.append("BIND(${renderExpression(pattern.expression)} AS ${pattern.variable})")
            }
            is FilterPatternAst -> {
                sb.append("FILTER(${renderExpression(pattern.expression)})")
            }
            is SubSelectPatternAst -> {
                sb.append("{\n")
                sb.renderSelect(pattern.query)
                sb.append("}\n")
            }
        }
    }
    
    private fun renderTriplePattern(triple: TriplePatternAst, sb: StringBuilder) {
        sb.append(renderTerm(triple.subject))
        sb.append(" ")
        sb.append(renderTerm(triple.predicate))
        sb.append(" ")
        sb.append(renderTerm(triple.obj))
    }
    
    // ============================================================================
    // PROPERTY PATHS
    // ============================================================================
    
    private fun renderPropertyPath(path: PropertyPathAst): String = when (path) {
        is BasicPathAst -> renderTerm(path.term)
        is OneOrMorePathAst -> "${renderPropertyPath(path.path)}+"
        is ZeroOrMorePathAst -> "${renderPropertyPath(path.path)}*"
        is ZeroOrOnePathAst -> "${renderPropertyPath(path.path)}?"
        is InversePathAst -> "^${renderPropertyPath(path.path)}"
        is NegationPathAst -> "!${renderPropertyPath(path.path)}"
        is AlternativePathAst -> {
            val leftStr = renderPropertyPath(path.left)
            val rightStr = renderPropertyPath(path.right)
            "${leftStr}|${rightStr}"
        }
        is SequencePathAst -> "${renderPropertyPath(path.left)}/${renderPropertyPath(path.right)}"
        is RangePathAst -> {
            val pathStr = renderPropertyPath(path.path)
            when {
                path.max == null -> "$pathStr{${path.min},}"
                path.min == 0 -> "$pathStr{,${path.max}}"
                path.min == path.max -> "$pathStr{${path.min}}"
                else -> "$pathStr{${path.min},${path.max}}"
            }
        }
    }
    
    // ============================================================================
    // EXPRESSIONS
    // ============================================================================
    
    private fun renderExpression(expr: ExpressionAst): String = when (expr) {
        is TermExpressionAst -> renderTerm(expr.term)
        is ComparisonExpressionAst -> {
            "${renderExpression(expr.left)} ${expr.operator.symbol} ${renderExpression(expr.right)}"
        }
        is AndExpressionAst -> {
            "(${renderExpression(expr.left)} && ${renderExpression(expr.right)})"
        }
        is OrExpressionAst -> {
            "(${renderExpression(expr.left)} || ${renderExpression(expr.right)})"
        }
        is NotExpressionAst -> "!(${renderExpression(expr.expression)})"
        is FunctionCallAst -> {
            "${expr.name}(${expr.arguments.joinToString(", ") { renderExpression(it) }})"
        }
        is ConditionalExpressionAst -> {
            "IF(${renderExpression(expr.condition)}, ${renderExpression(expr.thenValue)}, ${renderExpression(expr.elseValue)})"
        }
        is AggregateExpressionAst -> {
            val distinctStr = if (expr.distinct) "DISTINCT " else ""
            "${expr.function.functionName}($distinctStr${renderExpression(expr.expression)})"
        }
        is ArithmeticExpressionAst -> {
            "(${renderExpression(expr.left)} ${expr.operator.symbol} ${renderExpression(expr.right)})"
        }
        is FilterExpressionAst -> renderExpression(expr as ExpressionAst)
    }
    
    // ============================================================================
    // ORDER BY
    // ============================================================================
    
    private fun renderOrderClause(clause: OrderClauseAst): String {
        val expr = renderExpression(clause.expression)
        return when (clause.direction) {
            OrderDirection.ASC -> "$expr ASC"
            OrderDirection.DESC -> "$expr DESC"
        }
    }
    
    // ============================================================================
    // UPDATE OPERATIONS
    // ============================================================================
    
    private fun StringBuilder.renderUpdate(update: UpdateRequestAst) {
        renderVersionAndPrefixes(update.version, update.prefixes)
        update.operations.forEach { op ->
            renderUpdateOperation(op)
            append("\n")
        }
    }
    
    private fun StringBuilder.renderUpdateOperation(op: UpdateOperationAst) {
        renderUsingClauses(op.using, op.usingNamed)
        op.with?.let { append("WITH ${renderTerm(it)}\n") }
        
        when (op) {
            is InsertDataOperationAst -> {
                append("INSERT DATA {\n")
                op.data.forEach { triple ->
                    append("  ")
                    renderTriplePattern(triple, this)
                    append(" .\n")
                }
                append("}")
            }
            is DeleteDataOperationAst -> {
                append("DELETE DATA {\n")
                op.data.forEach { triple ->
                    append("  ")
                    renderTriplePattern(triple, this)
                    append(" .\n")
                }
                append("}")
            }
            is ModifyOperationAst -> {
                if (op.delete.isNotEmpty()) {
                    append("DELETE {\n")
                    op.delete.forEach { triple ->
                        append("  ")
                        renderTriplePattern(triple, this)
                        append(" .\n")
                    }
                    append("}\n")
                }
                if (op.insert.isNotEmpty()) {
                    append("INSERT {\n")
                    op.insert.forEach { triple ->
                        append("  ")
                        renderTriplePattern(triple, this)
                        append(" .\n")
                    }
                    append("}\n")
                }
                op.where?.let {
                    append("WHERE ")
                    renderGraphPattern(it, this)
                }
            }
            is DeleteWhereOperationAst -> {
                append("DELETE WHERE ")
                renderGraphPattern(op.where, this)
            }
            is LoadOperationAst -> {
                append("LOAD")
                if (op.silent) append(" SILENT")
                append(" ${renderTerm(op.source)}")
                op.into?.let { append(" INTO ${renderTerm(it)}") }
            }
            is ClearOperationAst -> {
                append("CLEAR")
                if (op.silent) append(" SILENT")
                if (op.graph != null) {
                    append(" GRAPH ${renderTerm(op.graph)}")
                } else {
                    append(" DEFAULT")
                }
            }
            is CreateOperationAst -> {
                append("CREATE")
                if (op.silent) append(" SILENT")
                append(" GRAPH ${renderTerm(op.graph)}")
            }
            is DropOperationAst -> {
                append("DROP")
                if (op.silent) append(" SILENT")
                if (op.graph != null) {
                    append(" GRAPH ${renderTerm(op.graph)}")
                } else {
                    append(" DEFAULT")
                }
            }
            is CopyOperationAst -> {
                append("COPY")
                if (op.silent) append(" SILENT")
                append(" ${renderTerm(op.source)} TO ${renderTerm(op.destination)}")
            }
            is MoveOperationAst -> {
                append("MOVE")
                if (op.silent) append(" SILENT")
                append(" ${renderTerm(op.source)} TO ${renderTerm(op.destination)}")
            }
            is AddOperationAst -> {
                append("ADD")
                if (op.silent) append(" SILENT")
                append(" ${renderTerm(op.source)} TO ${renderTerm(op.destination)}")
            }
        }
    }
    
    // ============================================================================
    // HELPERS
    // ============================================================================
    
    private fun StringBuilder.renderVersionAndPrefixes(version: String?, prefixes: List<PrefixDeclaration>) {
        version?.let {
            append("VERSION $it\n\n")
        }
        if (prefixes.isNotEmpty()) {
            prefixes.forEach { prefix ->
                append(prefix)
                append("\n")
            }
            append("\n")
        }
    }
    
    private fun StringBuilder.renderFromClauses(from: List<Iri>, fromNamed: List<Iri>) {
        from.forEach { iri ->
            append("FROM ${renderTerm(iri)}\n")
        }
        fromNamed.forEach { iri ->
            append("FROM NAMED ${renderTerm(iri)}\n")
        }
        if (from.isNotEmpty() || fromNamed.isNotEmpty()) {
            append("\n")
        }
    }
    
    private fun StringBuilder.renderUsingClauses(using: List<Iri>, usingNamed: List<Iri>) {
        using.forEach { iri ->
            append("USING ${renderTerm(iri)}\n")
        }
        usingNamed.forEach { iri ->
            append("USING NAMED ${renderTerm(iri)}\n")
        }
    }
    
    private fun renderTerm(term: RdfTerm): String = when (term) {
        is Var -> term.toString()
        is Iri -> term.toString()
        is BlankNode -> term.toString()
        is Literal -> term.toString()
        is TripleTerm -> term.toString()
    }
}

