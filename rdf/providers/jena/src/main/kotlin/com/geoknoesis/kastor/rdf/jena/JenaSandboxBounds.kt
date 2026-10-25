package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimits
import org.apache.jena.query.QueryCancelledException
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.SortCondition
import org.apache.jena.sparql.ARQConstants
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.algebra.OpVisitor
import org.apache.jena.sparql.algebra.TransformCopy
import org.apache.jena.sparql.algebra.Transformer
import org.apache.jena.sparql.algebra.op.OpGroup
import org.apache.jena.sparql.algebra.op.OpOrder
import org.apache.jena.sparql.algebra.op.OpTopN
import org.apache.jena.sparql.algebra.op.OpUnfold
import org.apache.jena.sparql.algebra.optimize.OptimizerStd
import org.apache.jena.sparql.algebra.optimize.Rewrite
import org.apache.jena.sparql.algebra.optimize.RewriteFactory
import org.apache.jena.sparql.algebra.walker.Walker
import org.apache.jena.sparql.core.VarExprList
import org.apache.jena.sparql.engine.ExecutionContext
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.expr.E_Function
import org.apache.jena.sparql.expr.Expr
import org.apache.jena.sparql.expr.ExprAggregator
import org.apache.jena.sparql.expr.ExprEvalException
import org.apache.jena.sparql.expr.ExprFunction
import org.apache.jena.sparql.expr.ExprFunction0
import org.apache.jena.sparql.expr.ExprFunction1
import org.apache.jena.sparql.expr.ExprFunction2
import org.apache.jena.sparql.expr.ExprFunction3
import org.apache.jena.sparql.expr.ExprFunctionN
import org.apache.jena.sparql.expr.ExprFunctionOp
import org.apache.jena.sparql.expr.ExprList
import org.apache.jena.sparql.expr.ExprNone
import org.apache.jena.sparql.expr.ExprTransformCopy
import org.apache.jena.sparql.expr.ExprTransformer
import org.apache.jena.sparql.expr.ExprTripleTerm
import org.apache.jena.sparql.expr.ExprVar
import org.apache.jena.sparql.expr.ExprVisitor
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.expr.aggregate.AggGroupConcat
import org.apache.jena.sparql.expr.aggregate.AggGroupConcatDistinct
import org.apache.jena.sparql.expr.aggregate.Aggregator
import org.apache.jena.sparql.function.Function
import org.apache.jena.sparql.function.FunctionEnv
import org.apache.jena.sparql.function.FunctionRegistry
import org.apache.jena.sparql.util.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * The deadline and cancellation that the expressions of one sandboxed query (and its regular expressions, while they
 * work) check: the deadline [deadline] on [clock] ([Long.MAX_VALUE] for none), the execution's cancel signal (Jena's
 * own timeout and `abort`), and the thread's interrupt flag (kept). Each failure is a [QueryCancelledException], as for
 * a timeout.
 */
internal class JenaSandboxBudget(private val deadline: Long, private val clock: () -> Long) {
    fun check(env: FunctionEnv?) {
        if (Thread.currentThread().isInterrupted) throw QueryCancelledException()
        if ((env as? ExecutionContext)?.cancelSignal?.get() == true) throw QueryCancelledException()
        if (deadline != Long.MAX_VALUE && clock() - deadline >= 0) throw QueryCancelledException()
    }
}

/**
 * The bounds of one sandboxed execution on Jena: set as its optimizer ([ARQConstants.sysOptimizerFactory]), it rewrites
 * the algebra Jena evaluates so that **every** function expression is a [BoundedExpr] (checked against [JenaCost] and
 * [SandboxLimits], and against [budget] before it runs) and every `GROUP_CONCAT` argument a [CountedExpr]. The
 * expressions are bounded before Jena's standard optimizer runs (without constant folding, which would evaluate them
 * while planning) and again after it, for the expressions it creates; the result is then verified ([verify]), and
 * [applied] records that this happened, which [JenaQuerySandbox.requireBounded] checks after the plan is made.
 */
internal class JenaSandboxRewrite(val budget: JenaSandboxBudget) : RewriteFactory {

    /** Set once the plan of the execution was bounded and verified. */
    @Volatile var applied: Boolean = false
        private set

    /** Characters the `GROUP_CONCAT` aggregates of this execution accumulated. */
    internal val groupConcat = AtomicLong()

    override fun create(context: Context): Rewrite = Rewrite { op ->
        val optimized = NoFolding(context).rewrite(bound(op))
        bound(optimized).also {
            JenaSandboxBounds.verify(it)
            applied = true
        }
    }

    /** [op] with every expression bounded (idempotent: bounded expressions are kept as they are). */
    fun bound(op: Op): Op = Transformer.transform(Ops(), Exprs(), op)

    fun bound(expr: Expr): Expr = ExprTransformer.transform(Exprs(), expr)

    /** Jena's standard optimizer without constant folding. */
    private class NoFolding(context: Context) : OptimizerStd(context) {
        override fun transformExprConstantFolding(op: Op): Op = op
    }

    private inner class Exprs : ExprTransformCopy() {
        override fun transform(func: ExprFunction0): Expr = wrap(func)
        override fun transform(func: ExprFunction1, expr1: Expr): Expr =
            if (func is CountedExpr) func.copy(expr1) else wrap(super.transform(func, expr1))
        override fun transform(func: ExprFunction2, expr1: Expr, expr2: Expr): Expr = wrap(super.transform(func, expr1, expr2))
        override fun transform(func: ExprFunction3, expr1: Expr, expr2: Expr, expr3: Expr): Expr =
            wrap(super.transform(func, expr1, expr2, expr3))
        override fun transform(func: ExprFunctionN, args: ExprList): Expr =
            if (func is BoundedExpr) func.copy(args) else wrap(super.transform(func, args))
        override fun transform(funcOp: ExprFunctionOp, args: ExprList, opArg: Op): Expr = funcOp.copy(args, bound(opArg))

        private fun wrap(expr: Expr): Expr {
            if (expr !is ExprFunction || expr is BoundedExpr || expr is CountedExpr || expr is ExprFunctionOp) return expr
            val cost = JenaCost.of(expr) ?: throw IllegalArgumentException("A sandboxed query may not use ${expr.functionSymbol.symbol}")
            return BoundedExpr(expr, cost, budget)
        }
    }

    /** The expressions Jena's own transformer does not reach: aggregates, `GROUP BY`, top-N sort conditions. */
    private inner class Ops : TransformCopy() {
        override fun transform(opGroup: OpGroup, subOp: Op): Op {
            val groupVars = VarExprList().also { list ->
                opGroup.groupVars.vars.forEach { v -> opGroup.groupVars.getExpr(v)?.let { list.add(v, bound(it)) } ?: list.add(v) }
            }
            return OpGroup.create(subOp, groupVars, opGroup.aggregators.map { ExprAggregator(it.aggVar.asVar(), aggregator(it.aggregator)) })
        }

        override fun transform(opOrder: OpOrder, subOp: Op): Op = OpOrder(subOp, conditions(opOrder.conditions))

        override fun transform(opTop: OpTopN, subOp: Op): Op = OpTopN(subOp, opTop.limit, conditions(opTop.conditions))

        private fun conditions(conditions: List<SortCondition>) = conditions.map { SortCondition(bound(it.expression), it.direction) }

        private fun aggregator(aggregator: Aggregator): Aggregator {
            val args = aggregator.exprList ?: return aggregator
            val separator = when (aggregator) {
                is AggGroupConcat -> aggregator.separator
                is AggGroupConcatDistinct -> aggregator.separator
                else -> return aggregator.copy(ExprList(args.list.map { bound(it) }))
            }
            val counted = args.list.map { arg -> if (arg is CountedExpr) arg else CountedExpr(bound(arg), groupConcat, separator?.length ?: 1, budget) }
            return aggregator.copy(ExprList(counted))
        }
    }
}

/**
 * A function expression of a sandboxed query, bounded: [budget] is checked before it runs, its [cost] checked from its
 * evaluated arguments before it computes (or computed here: `REGEX`, `REPLACE`), and its result checked against
 * [SandboxLimits] unless it only copies an argument ([JenaCost.COPYING]). Its arguments are those of [inner] (each a
 * bounded expression itself), so Jena's walkers and transformers see them as its own; [inner] itself is reached only
 * through this expression.
 */
internal class BoundedExpr(val inner: ExprFunction, private val cost: JenaCost, private val budget: JenaSandboxBudget) :
    ExprFunctionN(SYMBOL, ExprList(inner.args)) {

    private val label: String = (inner as? E_Function)?.functionIRI ?: inner.functionSymbol.symbol
    private val evaluation = JenaCostEvaluation(cost, label)

    /** The function an `E_Function` with an argument check calls, built once from the execution's registry. */
    @Volatile private var function: Function? = null

    override fun evalSpecial(binding: Binding, env: FunctionEnv): NodeValue = JenaSandboxBounds.failing {
        budget.check(env)
        val value = if (cost.evaluatesArguments) {
            val args = inner.args.map { it.eval(binding, env) }
            val check = Runnable { budget.check(env) }
            when (cost) {
                JenaCost.REGEX -> evaluation.regex(args, check)
                JenaCost.REPLACE -> evaluation.replace(args, check)
                else -> {
                    evaluation.precheck(args)
                    compute(args, binding, env)
                }
            }
        } else {
            inner.eval(binding, env)
        }
        if (cost != JenaCost.COPYING) JenaSandboxBounds.checkResult(value, label)
        value
    }

    /** [inner] applied to its evaluated [args], without evaluating them again. */
    private fun compute(args: List<NodeValue>, binding: Binding, env: FunctionEnv): NodeValue = when (val f = inner) {
        is E_Function -> function(f, env).exec(binding, ExprList(args), f.functionIRI, env)
        is ExprFunction1 -> f.eval(args[0], env)
        is ExprFunction2 -> f.eval(args[0], args[1], env)
        is ExprFunction3 -> f.eval(args[0], args[1], args[2], env)
        is ExprFunctionN -> f.eval(args, env)
        else -> f.eval(binding, env)
    }

    private fun function(f: E_Function, env: FunctionEnv): Function = function ?: run {
        val iri = f.functionIRI
        val built = FunctionRegistry.get(env.context).getFunction(iri)
            ?: throw ExprEvalException("URI <$iri> has no registered function factory")
        built.build(iri, ExprList(f.args), env.context)
        built
    }.also { function = it }

    override fun eval(args: List<NodeValue>): NodeValue = throw IllegalStateException("A bounded expression is evaluated by evalSpecial")

    override fun copy(newArgs: ExprList): Expr = BoundedExpr(rebuild(inner, newArgs.list), cost, budget)

    override fun equals(other: Expr, bySyntax: Boolean): Boolean = other is BoundedExpr && inner.equals(other.inner, bySyntax)

    override fun hashCode(): Int = inner.hashCode() * 31 + 7

    override fun getFunctionPrintName(cxt: org.apache.jena.sparql.serializer.SerializationContext?): String = inner.getFunctionPrintName(cxt)

    override fun getFunctionName(cxt: org.apache.jena.sparql.serializer.SerializationContext?): String = inner.getFunctionName(cxt)

    private companion object {
        const val SYMBOL = "kastor:bounded"

        fun rebuild(f: ExprFunction, args: List<Expr>): ExprFunction = when (f) {
            is ExprFunction0 -> f.copy()
            is ExprFunction1 -> f.copy(args[0])
            is ExprFunction2 -> f.copy(args[0], args[1])
            is ExprFunction3 -> f.copy(args[0], args[1], args[2])
            is ExprFunctionN -> f.copy(ExprList(args))
            else -> throw IllegalStateException("Cannot rebuild $f")
        } as ExprFunction
    }
}

/**
 * The argument of a `GROUP_CONCAT` aggregate, counted: every value adds its length (and the separator's, [separator])
 * to the execution's [counter], and more than [SandboxLimits.MAX_GROUP_CONCAT_LENGTH] characters fail the query.
 */
internal class CountedExpr(
    expr: Expr,
    private val counter: AtomicLong,
    private val separator: Int,
    private val budget: JenaSandboxBudget,
) : ExprFunction1(expr, SYMBOL) {

    override fun evalSpecial(binding: Binding, env: FunctionEnv): NodeValue = JenaSandboxBounds.failing {
        budget.check(env)
        val value = expr.eval(binding, env)
        val length = JenaSandboxBounds.textLength(value.asNode())
        val total = counter.addAndGet(length.toLong() + separator)
        if (total > SandboxLimits.MAX_GROUP_CONCAT_LENGTH) {
            throw SandboxLimitException("GROUP_CONCAT would accumulate more than ${SandboxLimits.MAX_GROUP_CONCAT_LENGTH} characters")
        }
        value
    }

    override fun eval(v: NodeValue): NodeValue = v

    override fun copy(expr: Expr): Expr = CountedExpr(expr, counter, separator, budget)

    override fun equals(other: Expr, bySyntax: Boolean): Boolean = other is CountedExpr && expr.equals(other.expr, bySyntax)

    override fun hashCode(): Int = expr.hashCode() * 31 + 11

    private companion object {
        const val SYMBOL = "kastor:counted"
    }
}

/**
 * A [SandboxLimitException] raised while Jena evaluates a sandboxed query, as the one exception Jena never turns into
 * `false` or an error value: a `FILTER` catches every other exception (it would make an exceeded limit a silent
 * non-match, which could hide a SHACL violation), but it lets a cancellation through.
 */
internal class JenaSandboxLimitExceeded(cause: SandboxLimitException) : QueryCancelledException(cause) {
    override val message: String? get() = cause?.message
}

/** Walking, verifying and checking the results of sandboxed Jena algebra. */
internal object JenaSandboxBounds {

    /** [block], with a [SandboxLimitException] it raises turned into [JenaSandboxLimitExceeded]. */
    inline fun <T> failing(block: () -> T): T = try {
        block()
    } catch (e: SandboxLimitException) {
        throw JenaSandboxLimitExceeded(e)
    }

    /** Most nodes a computed triple term may hold (a chain of `TRIPLE(?t, :p, ?t)` doubles them at every step). */
    const val MAX_TRIPLE_TERM_NODES: Int = 4096

    /**
     * The length of the text `GROUP_CONCAT` appends for [node]: the lexical form of a literal, the IRI of an IRI, the
     * label of a blank node; a triple term counts its components.
     */
    fun textLength(node: org.apache.jena.graph.Node): Int = when {
        node.isLiteral -> node.literalLexicalForm.length
        node.isURI -> node.uri.length
        node.isBlank -> node.blankNodeLabel.length
        node.isTripleTerm -> node.triple.let { textLength(it.subject) + textLength(it.predicate) + textLength(it.`object`) }
        else -> 0
    }

    /** Fails with [SandboxLimitException] when [value], computed by [what], exceeds the [SandboxLimits]. */
    fun checkResult(value: NodeValue, what: String) {
        when {
            value.isInteger -> SandboxLimits.checkIntegerBits(value.integer.bitLength().toLong(), what)
            value.isDecimal -> value.decimal.let { SandboxLimits.checkDecimal(it.unscaledValue().bitLength().toLong(), it.scale().toLong(), what) }
            else -> {
                val node = value.asNode()
                when {
                    node.isLiteral -> SandboxLimits.checkStringLength(node.literalLexicalForm.length.toLong(), what)
                    node.isURI -> SandboxLimits.checkStringLength(node.uri.length.toLong(), what)
                    node.isTripleTerm -> if (nodes(node, MAX_TRIPLE_TERM_NODES + 1) > MAX_TRIPLE_TERM_NODES) {
                        throw SandboxLimitException("$what would compute a triple term of more than $MAX_TRIPLE_TERM_NODES terms")
                    }
                }
            }
        }
    }

    /** The terms of [node] counted as a tree, stopping at [limit]: a triple term that shares components costs no more. */
    private fun nodes(node: org.apache.jena.graph.Node, limit: Int): Int {
        if (!node.isTripleTerm) return 1
        var count = 1
        for (part in listOf(node.triple.subject, node.triple.predicate, node.triple.`object`)) {
            count += nodes(part, limit - count)
            if (count >= limit) return count
        }
        return count
    }

    /**
     * Fails with [IllegalStateException] unless every function expression of [op] (anywhere: `EXISTS` patterns, sort
     * conditions, `GROUP BY`, aggregates) is bounded. This is what makes the rewrite fail closed: an expression a
     * Jena transform introduced or a transformer did not reach stops the query instead of running unbounded.
     */
    fun verify(op: Op) = walk(op, object : ExprVisitorAdapter() {
        override fun function(func: ExprFunction) {
            if (func !is BoundedExpr && func !is CountedExpr && func !is ExprFunctionOp) {
                throw IllegalStateException("The sandboxed plan holds an unbounded expression: $func")
            }
        }

        override fun tripleTerm(term: ExprTripleTerm) {
            if (!term.node.isConcrete) throw IllegalStateException("The sandboxed plan holds an unbounded triple-term expression: $term")
        }
    }, org.apache.jena.sparql.algebra.OpVisitorBase())

    /**
     * Visits every operator of [op] with [ops] and every expression with [exprs] (bottom up), including what Jena's
     * walker skips: the patterns of `EXISTS` / `NOT EXISTS`, sort conditions (`ORDER BY` and top-N), `GROUP BY`
     * expressions and aggregate arguments, and the expression of `UNFOLD`.
     */
    fun walk(op: Op, exprs: ExprVisitor, ops: OpVisitor) {
        lateinit var all: ExprVisitor
        val opWalker = object : OpVisitor by ops {
            override fun visit(opOrder: OpOrder) {
                opOrder.conditions.forEach { Walker.walk(it.expression, all) }
                ops.visit(opOrder)
            }
            override fun visit(opTop: OpTopN) {
                opTop.conditions.forEach { Walker.walk(it.expression, all) }
                ops.visit(opTop)
            }
            override fun visit(opGroup: OpGroup) {
                opGroup.groupVars.exprs.values.forEach { Walker.walk(it, all) }
                opGroup.aggregators.forEach { agg -> agg.aggregator.exprList?.forEach { Walker.walk(it, all) } }
                ops.visit(opGroup)
            }
            override fun visit(opUnfold: OpUnfold) {
                Walker.walk(opUnfold.expr, all)
                ops.visit(opUnfold)
            }
        }
        all = object : ExprVisitor by exprs {
            override fun visit(funcOp: ExprFunctionOp) {
                exprs.visit(funcOp)
                walk(funcOp.graphPattern, exprs, ops)
            }
            override fun visit(eAgg: ExprAggregator) {
                exprs.visit(eAgg)
                eAgg.aggregator.exprList?.forEach { Walker.walk(it, this) }
            }
        }
        Walker.walk(op, opWalker, all)
    }

    /** An [ExprVisitor] that hands every function expression to [function] and ignores the rest. */
    abstract class ExprVisitorAdapter : ExprVisitor {
        abstract fun function(func: ExprFunction)

        /** A triple-term expression `<<( s p o )>>`; ignored unless overridden. */
        open fun tripleTerm(term: ExprTripleTerm) = Unit
        override fun visit(func: ExprFunction0) = function(func)
        override fun visit(func: ExprFunction1) = function(func)
        override fun visit(func: ExprFunction2) = function(func)
        override fun visit(func: ExprFunction3) = function(func)
        override fun visit(func: ExprFunctionN) = function(func)
        override fun visit(funcOp: ExprFunctionOp) = function(funcOp)
        override fun visit(exprTripleTerm: ExprTripleTerm) = tripleTerm(exprTripleTerm)
        override fun visit(nv: NodeValue) = Unit
        override fun visit(nv: ExprVar) = Unit
        override fun visit(eAgg: ExprAggregator) = Unit
        override fun visit(exprNone: ExprNone) = Unit
    }

    /** Fails a sandboxed [exec] whose plan was not bounded ([JenaSandboxRewrite]); call it once the plan is made. */
    fun requireBounded(exec: QueryExecution) {
        val rewrite = exec.context?.get<Any>(ARQConstants.sysOptimizerFactory) as? JenaSandboxRewrite ?: return
        if (!rewrite.applied) throw IllegalStateException("The sandboxed query was planned without its bounds")
    }
}
