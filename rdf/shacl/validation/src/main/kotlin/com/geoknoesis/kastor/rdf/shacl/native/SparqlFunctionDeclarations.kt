package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
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

/**
 * SHACL 1.2 SPARQL §7: compiles the `sh:ListParameterExpressionFunction` declarations of a shapes graph whose
 * `sh:bodyExpression` is a SPARQL node expression (§6, `sh:select` / `sh:sparqlExpr`) into a [SparqlFunctionLibrary]
 * that every SHACL query carries ([SandboxedSelectQuery.functions]).
 *
 * The scope of a body pre-binds `this` to the function IRI (§7.3) and `arg<i>` to argument `i` (§6: a scope
 * variable named by a number is bound as `"arg" + str(name)`); an unbound optional argument leaves its variable
 * unbound. A body query is sent with the same library, so functions can call each other and themselves; recursion
 * is bounded by the provider's [com.geoknoesis.kastor.rdf.MAX_FUNCTION_DEPTH]. A call returns the projected node only
 * when exactly one solution binds it; zero or several are an error (§7.3), which the provider turns into an unbound
 * value, never a validation failure.
 *
 * Functions whose body is another kind of node expression (`shnex:`) are not compiled; feature detection reports
 * them when SPARQL calls them ([compiles]).
 */
internal object SparqlFunctionDeclarations {

    private val ARGUMENT_NAME = Regex("arg(0|[1-9][0-9]*)")

    private class Declaration(val iri: Iri, val optional: List<Boolean>, val body: NodeExpression.Select)

    /** The library of the functions of [index] with a SPARQL body; [SparqlFunctionLibrary.EMPTY] when there are none. */
    fun compile(index: ShapeGraphIndex): SparqlFunctionLibrary {
        val byIri = LinkedHashMap<String, SparqlFunction>()
        // Bodies pass this library on to their own queries: it is complete before any query runs.
        val library = SparqlFunctionLibrary { byIri[it] }
        for (candidate in candidates(index)) {
            index.tick("SPARQL function compilation")
            val declaration = declaration(candidate, index) ?: continue
            byIri[declaration.iri.value] = SparqlFunction(declaration.iri.value, declaration.optional) { call ->
                evaluate(declaration, call, library)
            }
        }
        return if (byIri.isEmpty()) SparqlFunctionLibrary.EMPTY else library
    }

    /**
     * Whether [function] is in the library [compile] builds, or is ill-formed so that [compile] throws: false for a
     * function SPARQL queries cannot call (not a `sh:ListParameterExpressionFunction`, or a body that is not a
     * SPARQL node expression).
     */
    fun compiles(function: Iri, index: ShapeGraphIndex): Boolean =
        try {
            declaration(function, index) != null
        } catch (_: ShapeCompileException) {
            true
        }

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

    /** The declaration of [node], or null when it is not a function with a SPARQL body; throws when ill-formed. */
    private fun declaration(node: RdfResource, index: ShapeGraphIndex): Declaration? {
        if (!index.isInstanceOf(node, ShaclSparqlTerms.ListParameterExpressionFunction)) return null
        val iri = node as? Iri ?: throw ShapeCompileException("SHACL function $node must be an IRI")
        val bodies = index.objects(iri, ShaclSparqlTerms.bodyExpression)
        if (bodies.size != 1) throw ShapeCompileException("SHACL function $iri must have exactly one sh:bodyExpression")
        val optional = parameters(iri, index)
        val preBound = LinkedHashSet<String>().apply {
            add("this")
            optional.indices.forEach { add("arg$it") }
        }
        val body = NodeExpressions.compile(bodies.single(), index, preBound) as? NodeExpression.Select ?: return null
        return Declaration(iri, optional, body)
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

    private fun evaluate(declaration: Declaration, call: SparqlFunctionCall, library: SparqlFunctionLibrary): RdfTerm? {
        val scope = LinkedHashMap<String, RdfTerm>()
        scope["this"] = declaration.iri
        call.arguments.forEachIndexed { i, argument -> if (argument != null) scope["arg$i"] = argument }
        val (query, bindings) = declaration.body.template.bind(scope)
        return call.select(SandboxedSelectQuery(query, library), bindings) { rows ->
            var result: RdfTerm? = null
            for (row in rows) {
                val term = row.get(declaration.body.variable) ?: continue
                if (result != null) return@select null
                result = term
            }
            result
        }
    }
}
