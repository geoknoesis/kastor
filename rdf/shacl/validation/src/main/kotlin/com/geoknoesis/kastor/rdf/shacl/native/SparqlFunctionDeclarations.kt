package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MAX_FUNCTION_DEPTH
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlFunction
import com.geoknoesis.kastor.rdf.SparqlFunctionCall
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.util.concurrent.atomic.AtomicInteger

/**
 * SHACL 1.2 SPARQL §7: compiles the `sh:ListParameterExpressionFunction` declarations of a shapes graph into a
 * [SparqlFunctionDeclarationSet], whose per-run [SparqlFunctionLibrary] every SHACL query carries
 * ([SandboxedSelectQuery.functions]).
 *
 * A body that is a SPARQL node expression (§6, `sh:select` / `sh:sparqlExpr`) is a query: its scope pre-binds `this`
 * to the function IRI (§7.3) and `arg<i>` to argument `i` (§6: a scope variable named by a number is bound as
 * `"arg" + str(name)`); an unbound optional argument leaves its variable unbound. A body query is sent with the same
 * library, so functions can call each other and themselves; recursion is bounded by the provider's
 * [com.geoknoesis.kastor.rdf.MAX_FUNCTION_DEPTH]. A call returns the projected node only when exactly one solution
 * binds it; zero or several are an error (§7.3), which the provider turns into an unbound value, never a validation
 * failure.
 *
 * Any other body that is a well-formed node expression (SHACL 1.2 Node Expressions) is evaluated by the native
 * evaluator of the run ([SparqlFunctionDeclarationSet.library]). A body that is no node expression at all is not
 * compiled; feature detection reports the function when SPARQL calls it ([compiles]). An ill-formed declaration (of
 * an IRI) is a compile error only when a SPARQL query calls the function ([calledFromSparql]) or a node expression
 * does ([NodeExpressionFunctions]); otherwise the function is left out.
 */
internal object SparqlFunctionDeclarations {

    private val ARGUMENT_NAME = Regex("arg(0|[1-9][0-9]*)")

    /** The declarations of the functions of [index] with a SPARQL or node expression body. */
    fun compile(index: ShapeGraphIndex): SparqlFunctionDeclarationSet {
        val declarations = ArrayList<SparqlFunctionDeclarationSet.Declaration>()
        for (candidate in candidates(index)) {
            index.tick("SPARQL function compilation")
            declarations.add(declaration(candidate, index) ?: continue)
        }
        return if (declarations.isEmpty()) SparqlFunctionDeclarationSet.EMPTY else SparqlFunctionDeclarationSet(declarations)
    }

    /**
     * Whether [function] is in the set [compile] builds, or is ill-formed so that [compile] throws: false for a
     * function SPARQL queries cannot call (not a `sh:ListParameterExpressionFunction`, or a body that is no node
     * expression at all).
     */
    fun compiles(function: Iri, index: ShapeGraphIndex): Boolean =
        try {
            declaration(function, index) != null
        } catch (_: ShapeCompileException) {
            true
        }

    /**
     * Whether a SPARQL query of the shapes graph (`sh:select`, `sh:ask`, `sh:sparqlExpr`) may call [function]: its
     * IRI is an IRI reference of the query, or a prefixed name `p:local` followed by `(` resolves to it through the
     * prefixes the query declares (`PREFIX`, or the `sh:prefixes` / `sh:declare` of the query node). The calls are
     * collected once per index, in one token scan of each query that ticks the budget
     * ([ShapeGraphIndex.sparqlFunctionCalls]), so asking for any number of functions costs a lookup each. A lexical
     * match, not a parse: an IRI reference outside a call counts as well, a name in a string or comment does not.
     */
    fun calledFromSparql(function: Iri, index: ShapeGraphIndex): Boolean = function.value in index.sparqlFunctionCalls

    /** Subjects with a `sh:bodyExpression` or typed `sh:ListParameterExpressionFunction`, in shapes-graph order. */
    private fun candidates(index: ShapeGraphIndex): Set<RdfResource> {
        val out = LinkedHashSet<RdfResource>()
        for (t in index.triples) {
            index.tick("SPARQL function compilation")
            if (t.predicate == ShaclSparqlTerms.bodyExpression ||
                (t.predicate == RDF.type && t.obj == ShaclSparqlTerms.ListParameterExpressionFunction)
            ) {
                out.add(t.subject)
            }
        }
        return out
    }

    /**
     * The declaration of [node], or null when it is not a function with a body SPARQL can call. A blank node function
     * throws; any other ill-formed declaration throws only when SPARQL calls the function ([calledFromSparql]), and
     * is left out otherwise (a node expression that calls it reports it, see [NodeExpressionFunctions]).
     */
    private fun declaration(node: RdfResource, index: ShapeGraphIndex): SparqlFunctionDeclarationSet.Declaration? {
        // Feature detection and compile both ask: the answer is computed once per index.
        val cache = index.sparqlFunctionDeclarations
        when (val known = cache[node]) {
            is SparqlFunctionDeclarationSet.Declaration -> return known
            is ShapeCompileException -> throw known
            NONE -> return null
        }
        return try {
            uncachedDeclaration(node, index).also { cache[node] = it ?: NONE }
        } catch (e: ShapeCompileException) {
            cache[node] = e
            throw e
        }
    }

    /** Marks a node without a declaration in [ShapeGraphIndex.sparqlFunctionDeclarations]. */
    private val NONE = Any()

    private fun uncachedDeclaration(node: RdfResource, index: ShapeGraphIndex): SparqlFunctionDeclarationSet.Declaration? {
        if (!index.isInstanceOf(node, ShaclSparqlTerms.ListParameterExpressionFunction)) return null
        val iri = node as? Iri ?: throw ShapeCompileException("SHACL function $node must be an IRI")
        return try {
            declaration(iri, index)
        } catch (e: ShapeCompileException) {
            if (calledFromSparql(iri, index)) throw e
            null
        }
    }

    private fun declaration(iri: Iri, index: ShapeGraphIndex): SparqlFunctionDeclarationSet.Declaration? {
        val bodies = index.objects(iri, ShaclSparqlTerms.bodyExpression)
        if (bodies.size != 1) throw ShapeCompileException("SHACL function $iri must have exactly one sh:bodyExpression")
        val optional = parameters(iri, index)
        val preBound = LinkedHashSet<String>().apply {
            add("this")
            optional.indices.forEach { add("arg$it") }
        }
        (NodeExpressions.compileSparqlOrConstant(bodies.single(), index, preBound) as? NodeExpression.Select)?.let {
            return SparqlFunctionDeclarationSet.Declaration(iri, optional, it)
        }
        return NodeExpressions.compile(bodies.single(), index, preBound)?.let { SparqlFunctionDeclarationSet.Declaration(iri, optional, it) }
    }

    /** Per argument position, whether the parameter is optional (§7.2: `sh:path shnex:arg<i>`, contiguous from 0). */
    private fun parameters(function: Iri, index: ShapeGraphIndex): List<Boolean> {
        val byPosition = index.objects(function, SHACL.parameter).map { parameter ->
            index.tick("SPARQL function compilation")
            val node = parameter as? RdfResource
                ?: throw ShapeCompileException("Parameter $parameter of SHACL function $function must be a resource")
            val path = index.objects(node, SHACL.path).singleOrNull() as? Iri
                ?: throw ShapeCompileException("Parameter $node of SHACL function $function must have exactly one IRI sh:path")
            val name = SparqlQueryTemplate.localName(path)
            val position = ARGUMENT_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw ShapeCompileException("Parameter <${path.value}> of SHACL function $function must have a local name arg0, arg1, ...")
            val optional = index.objects(node, ShaclSparqlTerms.optional)
            if (optional.size > 1 || optional.any { it !is Literal || it.datatype != XSD.boolean }) {
                throw ShapeCompileException("Parameter $node of SHACL function $function must have at most one xsd:boolean sh:optional")
            }
            position to optional.any { isLexicallyTrue(it) }
        }.sortedBy { it.first }
        byPosition.forEachIndexed { i, (position, optional) ->
            if (position != i) {
                throw ShapeCompileException("The parameters of SHACL function $function must be arg0, arg1, ... without gaps or repetitions")
            }
            if (!optional && i > 0 && byPosition[i - 1].second) {
                throw ShapeCompileException("SHACL function $function has a mandatory parameter arg$i after an optional one")
            }
        }
        return byPosition.map { it.second }
    }
}

/**
 * The SHACL SPARQL functions of a shapes graph ([SparqlFunctionDeclarations]): immutable and shared by every
 * validation run of the compiled shapes graph, while each run builds the [library] it sends.
 */
internal class SparqlFunctionDeclarationSet(private val declarations: List<Declaration>) {

    /** A function: its IRI, which parameters are optional, and its body (a [NodeExpression.Select] or another node expression). */
    class Declaration(val iri: Iri, val optional: List<Boolean>, val body: NodeExpression)

    /** True when the shapes graph declares no function SPARQL can call. */
    val isEmpty: Boolean get() = declarations.isEmpty()

    /** The bodies that are not SPARQL queries, which the native evaluator evaluates (their shapes must be compiled). */
    val expressionBodies: List<NodeExpression> = declarations.map { it.body }.filter { it !is NodeExpression.Select }

    /**
     * The library of one validation run; [SparqlFunctionLibrary.EMPTY] when there are no functions. A SPARQL body is
     * evaluated as a query sent with this library. Any other body is evaluated by [run], with the function IRI as
     * focus node (SHACL 1.2 SPARQL §7.3) and the arguments as `shnex:arg 0`, `shnex:arg 1`, ... (Node Expressions
     * §6.2); [run] returns the single output node, or null for an error (none, several, or an evaluation failure).
     *
     * A node expression body runs its queries as new queries, which the provider's own nesting limit does not relate
     * to the calling query. So every call of a function of the library, of either kind, counts towards one limit:
     * at most [MAX_FUNCTION_DEPTH] calls are under way at a time (a run is single-threaded, so this is the nesting
     * depth), and a call beyond it is an error. Recursion through any mix of bodies therefore terminates.
     */
    fun library(run: (body: NodeExpression, function: Iri, arguments: List<RdfTerm?>) -> RdfTerm?): SparqlFunctionLibrary {
        if (declarations.isEmpty()) return SparqlFunctionLibrary.EMPTY
        val byIri = LinkedHashMap<String, SparqlFunction>()
        // Bodies pass this library on to their own queries: it is complete before any query runs.
        val library = SparqlFunctionLibrary { byIri[it] }
        val active = AtomicInteger()
        for (declaration in declarations) {
            val body = declaration.body
            byIri[declaration.iri.value] = SparqlFunction(declaration.iri.value, declaration.optional) { call ->
                if (active.incrementAndGet() > MAX_FUNCTION_DEPTH) {
                    active.decrementAndGet()
                    null
                } else {
                    try {
                        if (body is NodeExpression.Select) select(declaration.iri, body, call, library) else run(body, declaration.iri, call.arguments)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }
        return library
    }

    private fun select(function: Iri, body: NodeExpression.Select, call: SparqlFunctionCall, library: SparqlFunctionLibrary): RdfTerm? {
        val scope = LinkedHashMap<String, RdfTerm>()
        scope["this"] = function
        call.arguments.forEachIndexed { i, argument -> if (argument != null) scope["arg$i"] = argument }
        val (query, bindings) = body.template.bind(scope)
        return call.select(SandboxedSelectQuery(query, library), bindings) { rows ->
            var result: RdfTerm? = null
            for (row in rows) {
                val term = row.get(body.variable) ?: continue
                if (result != null) return@select null
                result = term
            }
            result
        }
    }

    companion object {
        val EMPTY = SparqlFunctionDeclarationSet(emptyList())
    }
}
