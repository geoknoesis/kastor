package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.Var
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.graph.Node
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.Syntax
import org.apache.jena.sparql.expr.E_NotExists
import org.apache.jena.sparql.expr.Expr
import org.apache.jena.sparql.expr.ExprAggregator
import org.apache.jena.sparql.expr.ExprFunctionOp
import org.apache.jena.sparql.syntax.Element
import org.apache.jena.sparql.syntax.ElementPathBlock
import org.apache.jena.sparql.syntax.ElementBind
import org.apache.jena.sparql.syntax.ElementFilter
import org.apache.jena.sparql.syntax.ElementGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.random.Random

/**
 * The renderer brackets expressions so that the text means the tree it was rendered from: every
 * expression kind, as an operand of every other, is rendered, parsed with Jena, and the parsed
 * expression is compared, node by node, with the source tree.
 */
class SparqlExpressionPrecedenceTest {

    private val a = Var("a")
    private val b = Var("b")
    private val c = Var("c")
    private val g = Var("g")
    private val p = Iri("urn:p")

    // ------------------------------------------------------------------ one notation for both trees

    private fun term(term: RdfTerm): String = when (term) {
        is Var -> "?${term.name}"
        is Iri -> "<${term.value}>"
        is LangString -> "\"${term.lexical}\"@${term.lang.lowercase()}"
        is Literal -> "\"${term.lexical}\"^^<${term.datatype.value}>"
        else -> error("not generated: $term")
    }

    private fun node(node: Node): String = when {
        node.isVariable -> "?${node.name}"
        node.isURI -> "<${node.uri}>"
        node.isLiteral && node.literalLanguage.isNotEmpty() -> "\"${node.literalLexicalForm}\"@${node.literalLanguage.lowercase()}"
        node.isLiteral -> "\"${node.literalLexicalForm}\"^^<${node.literalDatatypeURI}>"
        else -> error("not generated: $node")
    }

    /** The source tree as `(operator operand ...)`. */
    private fun shape(expr: ExpressionAst): String = when (expr) {
        is TermExpressionAst -> term(expr.term)
        is ComparisonExpressionAst -> "(${expr.operator.symbol} ${shape(expr.left)} ${shape(expr.right)})"
        is ArithmeticExpressionAst -> "(${expr.operator.symbol} ${shape(expr.left)} ${shape(expr.right)})"
        is AndExpressionAst -> "(&& ${shape(expr.left)} ${shape(expr.right)})"
        is OrExpressionAst -> "(|| ${shape(expr.left)} ${shape(expr.right)})"
        is NotExpressionAst -> "(! ${shape(expr.expression)})"
        is UnaryMinusExpressionAst -> "(- ${shape(expr.expression)})"
        is InExpressionAst -> (listOf(if (expr.negated) "notin" else "in", shape(expr.expression)) + expr.values.map(::shape)).joinToString(" ", "(", ")")
        is ExistsExpressionAst -> "(${if (expr.negated) "notexists" else "exists"} ${shape(expr.pattern)})"
        is FunctionCallAst -> (listOf(expr.name.removeSurrounding("<", ">").lowercase()) + expr.arguments.map(::shape)).joinToString(" ", "(", ")")
        is ConditionalExpressionAst -> "(if ${shape(expr.condition)} ${shape(expr.thenValue)} ${shape(expr.elseValue)})"
        is AggregateExpressionAst ->
            (listOf("agg:" + expr.function.functionName.lowercase().replace("_", "") + if (expr.distinct) ":distinct" else "") +
                listOfNotNull(expr.expression?.let(::shape))).joinToString(" ", "(", ")")
    }

    /** The pattern of an EXISTS: triple patterns and filters, in the order they are written. */
    private fun shape(pattern: GraphPatternAst): String = when (pattern) {
        is GroupPatternAst -> pattern.patterns.joinToString(" ", "{", "}") { shape(it) }
        is TriplePatternAst -> "[${term(pattern.subject)} ${term(pattern.predicate)} ${term(pattern.obj)}]"
        is FilterPatternAst -> "(filter ${shape(pattern.expression)})"
        else -> error("not generated: $pattern")
    }

    private fun shape(element: Element): String = when (element) {
        is ElementGroup -> element.elements.joinToString(" ", "{", "}") { shape(it) }
        is ElementPathBlock -> element.pattern.list.joinToString(" ") { "[${node(it.subject)} ${node(it.predicate)} ${node(it.`object`)}]" }
        is ElementFilter -> "(filter ${shape(element.expr)})"
        else -> error("unexpected element ${element.javaClass.name}: $element")
    }

    /** What Jena parsed, in the same notation. */
    private fun shape(expr: Expr): String = when {
        expr is ExprFunctionOp -> "(${if (expr is E_NotExists) "notexists" else "exists"} ${shape(expr.element)})"
        expr is ExprAggregator -> {
            val aggregator = expr.aggregator
            // AggCount, AggCountVar, AggCountVarDistinct, AggSumDistinct, AggGroupConcat, ...
            val kind = aggregator.javaClass.simpleName.removePrefix("Agg")
            val distinct = kind.endsWith("Distinct")
            val name = kind.removeSuffix("Distinct").removeSuffix("Var").lowercase()
            val arguments = aggregator.exprList?.list.orEmpty().map(::shape)
            (listOf("agg:$name" + if (distinct) ":distinct" else "") + arguments).joinToString(" ", "(", ")")
        }
        expr.isVariable -> "?${expr.varName}"
        expr.isConstant -> node(expr.constant.asNode())
        expr.isFunction -> {
            val function = expr.function
            val name = function.opName ?: function.functionIRI ?: function.functionSymbol.symbol.lowercase()
            (listOf(name) + function.args.map(::shape)).joinToString(" ", "(", ")")
        }
        else -> error("unexpected expression ${expr.javaClass.name}: $expr")
    }

    // ------------------------------------------------------------------ the places an expression is written

    private val pattern = GroupPatternAst(listOf(TriplePatternAst(a, p, b), TriplePatternAst(a, p, c)))

    private fun parse(text: String, what: String): Query = try {
        QueryFactory.create(text, Syntax.syntaxSPARQL_11)
    } catch (e: Exception) {
        fail("$what is not legal SPARQL: ${e.message}\n$text", e)
    }

    private fun projected(expr: ExpressionAst, groupBy: List<Var> = emptyList()): Expr {
        val where = if (groupBy.isEmpty()) pattern else GroupPatternAst(listOf(TriplePatternAst(g, p, b)))
        val text = SparqlRenderer.render(SelectQueryAst(listOf(AliasedSelectItemAst(expr, "r")), where = where, groupBy = groupBy))
        return parse(text, "projection").project.getExpr(org.apache.jena.sparql.core.Var.alloc("r"))
    }

    private fun bound(expr: ExpressionAst): Expr {
        val text = SparqlRenderer.render(SelectQueryAst(emptyList(), where = GroupPatternAst(pattern.patterns + BindPatternAst(Var("r"), expr))))
        return (parse(text, "BIND").queryPattern as ElementGroup).elements.filterIsInstance<ElementBind>().single().expr
    }

    private fun filtered(expr: FilterExpressionAst): Expr {
        val text = SparqlRenderer.render(SelectQueryAst(emptyList(), where = GroupPatternAst(pattern.patterns + FilterPatternAst(expr))))
        return (parse(text, "FILTER").queryPattern as ElementGroup).elements.filterIsInstance<ElementFilter>().single().expr
    }

    private fun ordered(expr: ExpressionAst, direction: OrderDirection): Expr {
        val text = SparqlRenderer.render(SelectQueryAst(emptyList(), where = pattern, orderBy = listOf(OrderClauseAst(expr, direction))))
        val condition = parse(text, "ORDER BY $direction").orderBy.single()
        if (direction == OrderDirection.DESC) assertEquals(Query.ORDER_DESCENDING, condition.direction, text)
        return condition.expression
    }

    /** `GROUP BY (expr AS ?r)` and, with a name of the parser's own, `GROUP BY (expr)`. */
    private fun grouped(expr: ExpressionAst, named: Boolean): Expr {
        val query = if (named) {
            SelectQueryAst(listOf(VariableSelectItemAst(Var("r"))), where = pattern, groupByExpressions = listOf(GroupConditionAst(expr, Var("r"))))
        } else {
            val count = AliasedSelectItemAst(AggregateExpressionAst(AggregateFunction.COUNT, null), "n")
            SelectQueryAst(listOf(count), where = pattern, groupByExpressions = listOf(GroupConditionAst(expr)))
        }
        val conditions = parse(SparqlRenderer.render(query), "GROUP BY").groupBy
        val variable = conditions.vars.single()
        // A condition that is a variable in brackets is held as that variable, without an expression.
        return conditions.getExpr(variable) ?: org.apache.jena.sparql.expr.ExprVar(variable)
    }

    private fun having(expr: FilterExpressionAst): Expr {
        val text = SparqlRenderer.render(
            SelectQueryAst(listOf(VariableSelectItemAst(g)), where = GroupPatternAst(listOf(TriplePatternAst(g, p, b))), groupBy = listOf(g), having = listOf(expr))
        )
        return parse(text, "HAVING").havingExprs.single()
    }

    /** Renders [expr] in every place it may stand and compares what Jena parsed with the tree. */
    private fun assertRoundTrips(expr: ExpressionAst, aggregates: Boolean, label: String) {
        val expected = shape(expr)
        val parsed = LinkedHashMap<String, Expr>()
        if (aggregates) {
            parsed["projection"] = projected(expr, listOf(g))
            if (expr is FilterExpressionAst) parsed["HAVING"] = having(expr)
        } else {
            parsed["projection"] = projected(expr)
            parsed["BIND"] = bound(expr)
            parsed["ORDER BY ASC"] = ordered(expr, OrderDirection.ASC)
            parsed["ORDER BY DESC"] = ordered(expr, OrderDirection.DESC)
            parsed["GROUP BY AS"] = grouped(expr, named = true)
            parsed["GROUP BY"] = grouped(expr, named = false)
            if (expr is FilterExpressionAst) parsed["FILTER"] = filtered(expr)
        }
        for ((place, actual) in parsed) assertEquals(expected, shape(actual), "$label in $place")
    }

    // ------------------------------------------------------------------ generated trees

    private class Generator(private val random: Random, private val variables: List<Var>, private val aggregates: Boolean) {
        private val constants: List<RdfTerm> = listOf(
            Literal("1", XSD.integer), Literal("-1", XSD.integer), Literal("0", XSD.integer), Literal("2.5", XSD.decimal),
            Literal("-2.5", XSD.decimal), Literal("1.0E0", XSD.double), Literal("x"), Literal("a b"), LangString("x", "en"),
            Literal("true", XSD.boolean), Literal("false", XSD.boolean), Iri("urn:x"), Literal("2026-10-01", XSD.date),
        )

        private fun leaf(): ExpressionAst = TermExpressionAst(if (random.nextInt(3) == 0) constants.random(random) else variables.random(random))

        /** Any expression; [inAggregate] forbids a nested aggregate. */
        fun expression(depth: Int, inAggregate: Boolean = false): ExpressionAst {
            if (depth <= 0) return leaf()
            return when (random.nextInt(if (aggregates && !inAggregate) 10 else 9)) {
                0 -> leaf()
                5 -> UnaryMinusExpressionAst(expression(depth - 1, inAggregate))
                1, 2 -> ArithmeticExpressionAst(expression(depth - 1, inAggregate), ArithmeticOperator.values().random(random), expression(depth - 1, inAggregate))
                3 -> {
                    val name = listOf("STR", "COALESCE", "CONCAT", "urn:fn", "<urn:other>").random(random)
                    FunctionCallAst(name, List(if (name == "STR") 1 else 1 + random.nextInt(3)) { expression(depth - 1, inAggregate) })
                }
                4 -> ConditionalExpressionAst(filter(depth - 1, inAggregate), expression(depth - 1, inAggregate), expression(depth - 1, inAggregate))
                9 -> {
                    val function = AggregateFunction.values().random(random)
                    val star = function == AggregateFunction.COUNT && random.nextInt(4) == 0
                    AggregateExpressionAst(
                        function,
                        if (star) null else expression(depth - 1, inAggregate = true),
                        distinct = random.nextBoolean(),
                        separator = if (function == AggregateFunction.GROUP_CONCAT && random.nextBoolean()) ", " else null,
                    )
                }
                else -> filter(depth, inAggregate)
            }
        }

        /** A boolean-valued expression. */
        fun filter(depth: Int, inAggregate: Boolean = false): FilterExpressionAst = when (if (depth <= 0) 0 else random.nextInt(9)) {
            0, 1, 2 -> ComparisonExpressionAst(expression(depth - 1, inAggregate), ComparisonOperator.values().random(random), expression(depth - 1, inAggregate))
            3 -> AndExpressionAst(filter(depth - 1, inAggregate), filter(depth - 1, inAggregate))
            4 -> OrExpressionAst(filter(depth - 1, inAggregate), filter(depth - 1, inAggregate))
            5 -> NotExpressionAst(filter(depth - 1, inAggregate))
            6 -> InExpressionAst(expression(depth - 1, inAggregate), List(random.nextInt(4)) { expression(depth - 1, inAggregate) }, negated = random.nextBoolean())
            // A pattern has no place in a grouped projection or HAVING; there a comparison stands in.
            7 -> if (aggregates) {
                ComparisonExpressionAst(expression(depth - 1, inAggregate), ComparisonOperator.values().random(random), expression(depth - 1, inAggregate))
            } else {
                val triples = List(1 + random.nextInt(2)) {
                    TriplePatternAst(variables.random(random), Iri("urn:p"), if (random.nextBoolean()) variables.random(random) else Iri("urn:o"))
                }
                val filters = if (random.nextBoolean()) listOf(FilterPatternAst(filter(depth - 1))) else emptyList()
                ExistsExpressionAst(GroupPatternAst(triples + filters), negated = random.nextBoolean())
            }
            else -> FunctionCallAst(listOf("COALESCE", "urn:fn").random(random), List(1 + random.nextInt(2)) { expression(depth - 1, inAggregate) })
        }
    }

    @Test
    fun `a comparison used as an arithmetic operand keeps its brackets`() {
        val comparison = ComparisonExpressionAst(TermExpressionAst(a), ComparisonOperator.EQ, TermExpressionAst(b))
        val sum = ArithmeticExpressionAst(comparison, ArithmeticOperator.ADD, TermExpressionAst(c))
        val text = SparqlRenderer.render(SelectQueryAst(listOf(AliasedSelectItemAst(sum, "r")), where = pattern))
        assertTrue(text.contains("((?a = ?b) + ?c)"), text)
        assertEquals("(+ (= ?a ?b) ?c)", shape(projected(sum)))
        val right = ArithmeticExpressionAst(TermExpressionAst(c), ArithmeticOperator.MULTIPLY, comparison)
        assertEquals("(* ?c (= ?a ?b))", shape(projected(right)))
    }

    @Test
    fun `every expression kind keeps its meaning as an operand of every other`() {
        val leaf = TermExpressionAst(a)
        val comparison = ComparisonExpressionAst(TermExpressionAst(a), ComparisonOperator.LT, TermExpressionAst(b))
        val operands: List<ExpressionAst> = listOf(
            leaf,
            TermExpressionAst(Literal("-1", XSD.integer)),
            comparison,
            ArithmeticExpressionAst(TermExpressionAst(a), ArithmeticOperator.SUBTRACT, TermExpressionAst(b)),
            ArithmeticExpressionAst(TermExpressionAst(a), ArithmeticOperator.DIVIDE, TermExpressionAst(b)),
            AndExpressionAst(comparison, comparison),
            OrExpressionAst(comparison, comparison),
            NotExpressionAst(comparison),
            FunctionCallAst("STR", listOf(leaf)),
            ConditionalExpressionAst(comparison, leaf, TermExpressionAst(b)),
            UnaryMinusExpressionAst(leaf),
            UnaryMinusExpressionAst(UnaryMinusExpressionAst(leaf)),
            InExpressionAst(leaf, listOf(TermExpressionAst(b), TermExpressionAst(Literal("1", XSD.integer)))),
            InExpressionAst(leaf, emptyList(), negated = true),
            ExistsExpressionAst(GroupPatternAst(listOf(TriplePatternAst(a, p, c), FilterPatternAst(comparison)))),
            ExistsExpressionAst(GroupPatternAst(listOf(TriplePatternAst(a, p, c))), negated = true),
        )
        var checked = 0
        for (x in operands) for (y in operands) {
            val parents = ArrayList<ExpressionAst>()
            ArithmeticOperator.values().forEach { parents += ArithmeticExpressionAst(x, it, y) }
            ComparisonOperator.values().forEach { parents += ComparisonExpressionAst(x, it, y) }
            parents += FunctionCallAst("COALESCE", listOf(x, y))
            parents += ConditionalExpressionAst(comparison, x, y)
            parents += UnaryMinusExpressionAst(x)
            parents += InExpressionAst(x, listOf(y, leaf))
            parents += InExpressionAst(x, listOf(y), negated = true)
            parents += ExistsExpressionAst(GroupPatternAst(listOf(TriplePatternAst(a, p, b), FilterPatternAst(ComparisonExpressionAst(x, ComparisonOperator.EQ, y)))))
            if (x is FilterExpressionAst) {
                parents += NotExpressionAst(x)
                parents += ConditionalExpressionAst(x, y, leaf)
                if (y is FilterExpressionAst) {
                    parents += AndExpressionAst(x, y)
                    parents += OrExpressionAst(x, y)
                }
            }
            for (parent in parents) {
                assertRoundTrips(parent, aggregates = false, label = shape(parent))
                checked++
            }
        }
        assertTrue(checked > 1000, "only $checked combinations")

        // Aggregates, as operands and with any expression inside.
        for (x in operands) for (function in AggregateFunction.values()) for (distinct in listOf(false, true)) {
            val aggregate = AggregateExpressionAst(function, x, distinct)
            val group = TermExpressionAst(g)
            for (parent in listOf<ExpressionAst>(
                aggregate,
                ArithmeticExpressionAst(aggregate, ArithmeticOperator.MULTIPLY, group),
                ArithmeticExpressionAst(group, ArithmeticOperator.SUBTRACT, aggregate),
                ComparisonExpressionAst(aggregate, ComparisonOperator.GT, ComparisonExpressionAst(group, ComparisonOperator.EQ, aggregate)),
                NotExpressionAst(ComparisonExpressionAst(aggregate, ComparisonOperator.NE, group)),
                AndExpressionAst(ComparisonExpressionAst(aggregate, ComparisonOperator.LTE, group), ComparisonExpressionAst(group, ComparisonOperator.GTE, aggregate)),
            )) {
                assertRoundTrips(parent, aggregates = true, label = shape(parent))
            }
        }
        assertRoundTrips(AggregateExpressionAst(AggregateFunction.COUNT, null), aggregates = true, label = "COUNT(*)")
        assertRoundTrips(AggregateExpressionAst(AggregateFunction.COUNT, null, distinct = true), aggregates = true, label = "COUNT(DISTINCT *)")
    }

    @Test
    fun `generated expression trees are parsed back as the same tree`() {
        val random = Random(20261001)
        val plain = Generator(random, listOf(a, b, c), aggregates = false)
        val shapes = HashSet<String>()
        repeat(400) { index ->
            val tree = if (index % 2 == 0) plain.expression(1 + random.nextInt(4)) else plain.filter(1 + random.nextInt(4))
            shapes += shape(tree)
            assertRoundTrips(tree, aggregates = false, label = "tree #$index ${shape(tree)}")
        }
        val grouped = Generator(random, listOf(g), aggregates = true)
        repeat(200) { index ->
            val tree = if (index % 2 == 0) grouped.expression(1 + random.nextInt(4)) else grouped.filter(1 + random.nextInt(4))
            shapes += shape(tree)
            assertRoundTrips(tree, aggregates = true, label = "grouped tree #$index ${shape(tree)}")
        }
        assertTrue(shapes.size > 450, "only ${shapes.size} distinct trees")
        assertTrue(shapes.any { it.contains("agg:") } && shapes.count { it.length > 80 } > 100, "the trees are too simple")
    }
}
