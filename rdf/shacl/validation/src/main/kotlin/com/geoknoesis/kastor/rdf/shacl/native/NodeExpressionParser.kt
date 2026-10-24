package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigInteger

/** Maximum nesting of a node expression in the shapes graph; deeper (or cyclic) expressions are ill-formed. */
internal const val MAX_NODE_EXPRESSION_NESTING = 128

/**
 * Parses SHACL 1.2 node expressions (Node Expressions §3–§6) into [NodeExpression] trees, enforcing the syntax rules of
 * Appendix A. Constants and SPARQL expressions (`sh:select`, `sh:sparqlExpr`) are compiled as before
 * ([NodeExpressions.compileSparqlOrConstant]); any other blank node is, in order:
 *
 * 1. an **empty expression** when it is the subject of no triple;
 * 2. a **list expression** when it is a SHACL list cell (all members constants);
 * 3. a **named parameter function** when it has the key parameter of a `shnex:` function or of a custom function
 *    declared in the shapes graph (§6.1). Only the properties of the function may occur, each at most once (§4);
 * 4. a **list parameter function** when its predicate is `shnex:conformsToShape`, a `sparql:` function (§5) or a
 *    custom list parameter function (§6.2): a single triple whose object is the list of arguments, `()`, or a single
 *    argument (§3.2.2);
 * 5. otherwise no node expression at all (null), unless it uses a `shnex:` predicate, which is then ill-formed.
 *
 * A blank node with the key parameters of two functions is ill-formed (the key parameters of all functions are
 * disjoint, §6.1). Ill-formed expressions throw [ShapeCompileException]. Every visited node ticks the preparation
 * budget, a node shared by several expressions is parsed once, and cycles or nesting beyond
 * [MAX_NODE_EXPRESSION_NESTING] are rejected, so a hostile shapes graph stays bounded.
 *
 * `sparql:` names are not checked against the §5 table here; an unknown name parses as a [NodeExpression.SparqlCall].
 */
internal class NodeExpressionParser(private val index: ShapeGraphIndex, private val preBound: Set<String>) {
    private val parsed = HashMap<BlankNode, NodeExpression?>()
    private val inProgress = LinkedHashSet<BlankNode>()

    /** The expression of [term], or null when [term] is a blank node that is no node expression. */
    fun parse(term: RdfTerm): NodeExpression? {
        NodeExpressions.compileSparqlOrConstant(term, index, preBound)?.let { return it }
        val node = term as? BlankNode ?: return null
        if (node in parsed) return parsed[node]
        if (node in inProgress) throw ShapeCompileException("Node expression $node is cyclic")
        if (inProgress.size >= MAX_NODE_EXPRESSION_NESTING) {
            throw ShapeCompileException("Node expression $node is nested more than $MAX_NODE_EXPRESSION_NESTING levels deep")
        }
        inProgress.add(node)
        try {
            return parseBlank(node).also { parsed[node] = it }
        } finally {
            inProgress.remove(node)
        }
    }

    /** The expression of [term], which must be a well-formed node expression: the value of [predicate] at [owner]. */
    private fun required(term: RdfTerm, owner: BlankNode, predicate: Iri): NodeExpression =
        parse(term) ?: throw ShapeCompileException("The value $term of ${name(predicate)} at node expression $owner must be a node expression")

    private fun parseBlank(node: BlankNode): NodeExpression? {
        val predicates = index.predicates(node)
        if (predicates.isEmpty()) return NodeExpression.Empty
        if (RDF.first in predicates || RDF.rest in predicates) return listExpression(node, predicates)

        val functions = NodeExpressionFunctions.of(index)
        val builtinKeys = predicates.filter { it in NodeExpressionVocabulary.BUILTIN }
        val customNamed = predicates.mapNotNull { functions.byKey[it] }.distinct()
        val listFunctions = predicates.filter { isListFunction(it) }
        val count = builtinKeys.size + customNamed.size + listFunctions.size
        if (count > 1) {
            val keys = (builtinKeys + customNamed.flatMap { it.keys.intersect(predicates) } + listFunctions).joinToString { name(it) }
            throw ShapeCompileException("Node expression $node has the key parameters of several functions: $keys")
        }
        return when {
            builtinKeys.isNotEmpty() -> builtin(node, builtinKeys.single(), predicates)
            customNamed.isNotEmpty() -> customNamed(node, customNamed.single(), predicates)
            listFunctions.isNotEmpty() -> listFunction(node, listFunctions.single(), predicates)
            predicates.any { it.value.startsWith(ShaclSparqlTerms.SHNEX_NAMESPACE) } ->
                throw ShapeCompileException(
                    "Node expression $node has no key parameter of a node expression function: ${predicates.joinToString { name(it) }}",
                )
            else -> null
        }
    }

    private fun isListFunction(predicate: Iri): Boolean =
        predicate == NodeExpressionVocabulary.conformsToShape ||
            predicate.value.startsWith(ShaclSparqlTerms.SPARQL_NAMESPACE) ||
            index.isInstanceOf(predicate, ShaclSparqlTerms.ListParameterExpressionFunction)

    /** §4.1.3: a non-empty SHACL list whose members are all constants. */
    private fun listExpression(node: BlankNode, predicates: Set<Iri>): NodeExpression {
        if (predicates.any { it != RDF.first && it != RDF.rest }) {
            throw ShapeCompileException("List expression $node must not be the subject of triples other than rdf:first / rdf:rest")
        }
        val members = index.parseRdfList(node).map { member ->
            index.tick("node expression compilation")
            val constant = NodeExpressions.compileSparqlOrConstant(member, index, preBound) as? NodeExpression.Constant
            constant ?: throw ShapeCompileException("Member $member of list expression $node must be a constant (an IRI, literal or triple term)")
        }
        return NodeExpression.ListExpr(members)
    }

    // --- named parameter functions -------------------------------------------------------------------------------

    /** The single value of each predicate of [node]; [allowed] are the properties of the function. */
    private fun properties(node: BlankNode, predicates: Set<Iri>, allowed: Set<Iri>, function: String): Map<Iri, RdfTerm> {
        val out = LinkedHashMap<Iri, RdfTerm>()
        for (p in predicates) {
            index.tick("node expression compilation")
            if (p !in allowed) throw ShapeCompileException("$function $node must not have ${name(p)}")
            val values = index.objects(node, p)
            if (values.size != 1) throw ShapeCompileException("$function $node must have at most one value for ${name(p)}")
            out[p] = values.single()
        }
        return out
    }

    private fun builtin(node: BlankNode, key: Iri, predicates: Set<Iri>): NodeExpression {
        val v = NodeExpressionVocabulary
        val function = "Node expression"
        val values = properties(node, predicates, v.BUILTIN.getValue(key) + key, function)
        fun expr(p: Iri) = values[p]?.let { required(it, node, p) }
        fun mandatory(p: Iri) = expr(p) ?: throw ShapeCompileException("$function $node with ${name(key)} must have ${name(p)}")
        val keyValue = values.getValue(key)
        return when (key) {
            v.`var` -> NodeExpression.Var(varName(node, keyValue))
            v.pathValues -> {
                index.tick("node expression compilation")
                NodeExpression.PathValues(ShaclPathParser.parse(keyValue, index), expr(v.focusNode))
            }
            v.exists -> NodeExpression.Exists(mandatory(key))
            v.`if` -> {
                if (v.then !in values && v.`else` !in values) {
                    throw ShapeCompileException("If expression $node must have shnex:then or shnex:else")
                }
                NodeExpression.If(mandatory(key), expr(v.then), expr(v.`else`))
            }
            v.distinct -> NodeExpression.Distinct(mandatory(key))
            v.intersection -> NodeExpression.Intersection(members(node, key, keyValue))
            v.concat -> NodeExpression.Concat(members(node, key, keyValue))
            v.remove -> NodeExpression.Remove(mandatory(key), mandatory(v.nodes))
            v.filterShape -> NodeExpression.FilterShape(shape(node, key, keyValue), mandatory(v.nodes))
            v.limit -> NodeExpression.Limit(nonNegativeInteger(node, key, keyValue), mandatory(v.nodes))
            v.offset -> NodeExpression.Offset(nonNegativeInteger(node, key, keyValue), mandatory(v.nodes))
            v.orderBy -> NodeExpression.OrderBy(mandatory(key), mandatory(v.nodes), values[v.desc]?.let { boolean(node, v.desc, it) } ?: false)
            v.flatMap -> NodeExpression.FlatMap(mandatory(key), expr(v.nodes))
            v.findFirst -> NodeExpression.FindFirst(shape(node, key, keyValue), expr(v.nodes))
            v.matchAll -> NodeExpression.MatchAll(shape(node, key, keyValue), expr(v.nodes))
            v.count -> NodeExpression.Count(mandatory(key))
            v.min -> NodeExpression.Min(mandatory(key))
            v.max -> NodeExpression.Max(mandatory(key))
            v.sum -> NodeExpression.Sum(mandatory(key))
            v.instancesOf -> NodeExpression.InstancesOf(mandatory(key))
            v.nodesMatching -> NodeExpression.NodesMatching(shape(node, key, keyValue))
            v.arg -> arg(node, keyValue)
            else -> error("unhandled node expression key parameter $key")
        }
    }

    /** §6.1: the given parameters of [function], each once; values are node expressions. */
    private fun customNamed(node: BlankNode, function: NamedParameterFunction, predicates: Set<Iri>): NodeExpression {
        val values = properties(node, predicates, function.parameters, "Custom named parameter expression")
        val args = LinkedHashMap<Iri, NodeExpression>()
        for ((p, value) in values) args[p] = required(value, node, p)
        return NodeExpression.CustomNamed(function.iri, args)
    }

    /** The members of the SHACL list [head], each a node expression (`shnex:intersection`, `shnex:concat`). */
    private fun members(node: BlankNode, key: Iri, head: RdfTerm): List<NodeExpression> {
        if (head != RDF.nil && !(head is RdfResource && index.objects(head, RDF.first).isNotEmpty())) {
            throw ShapeCompileException("The value of ${name(key)} at node expression $node must be a SHACL list")
        }
        return index.parseRdfList(head).map { required(it, node, key) }
    }

    private fun varName(node: BlankNode, value: RdfTerm): String {
        val literal = value as? Literal
        if (literal == null || literal.datatype != XSD.string || literal.lexical.isEmpty()) {
            throw ShapeCompileException("shnex:var of node expression $node must be a non-empty xsd:string")
        }
        return literal.lexical
    }

    /** A well-formed shape is a resource; its own well-formedness is checked where the shape is compiled. */
    private fun shape(node: BlankNode, key: Iri, value: RdfTerm): RdfResource =
        value as? RdfResource ?: throw ShapeCompileException("The value of ${name(key)} at node expression $node must be a shape")

    private fun nonNegativeInteger(node: BlankNode, key: Iri, value: RdfTerm): Int {
        val n = integer(value)
        if (n == null || n.signum() < 0) {
            throw ShapeCompileException("The value of ${name(key)} at node expression $node must be an xsd:integer of at least 0")
        }
        return clamp(n)
    }

    private fun boolean(node: BlankNode, key: Iri, value: RdfTerm): Boolean {
        val literal = value as? Literal
        if (literal == null || literal.datatype != XSD.boolean || literal.lexical !in BOOLEAN_LEXICALS) {
            throw ShapeCompileException("The value of ${name(key)} at node expression $node must be an xsd:boolean")
        }
        return literal.lexical == "true" || literal.lexical == "1"
    }

    /** §6.3: an IRI (a named parameter) or an xsd:integer (an argument position). */
    private fun arg(node: BlankNode, value: RdfTerm): NodeExpression =
        when (value) {
            is Iri -> NodeExpression.NamedArg(value)
            else -> integer(value)?.let { NodeExpression.Arg(clamp(it)) }
                ?: throw ShapeCompileException("shnex:arg of node expression $node must be an IRI or an xsd:integer")
        }

    // --- list parameter functions --------------------------------------------------------------------------------

    /** §3.2.2: a single triple whose object is `()`, a SHACL list of node expressions, or a single node expression. */
    private fun listFunction(node: BlankNode, function: Iri, predicates: Set<Iri>): NodeExpression {
        val objects = index.objects(node, function)
        if (predicates.size != 1 || objects.size != 1) {
            throw ShapeCompileException("List parameter function expression $node (${name(function)}) must be the subject of a single triple")
        }
        val o = objects.single()
        val args = when {
            o == RDF.nil -> emptyList()
            o is BlankNode && (index.objects(o, RDF.first).isNotEmpty() || index.objects(o, RDF.rest).isNotEmpty()) ->
                index.parseRdfList(o).map { required(it, node, function) }
            else -> listOf(required(o, node, function))
        }
        return when {
            function == NodeExpressionVocabulary.conformsToShape -> {
                if (args.size != 2) {
                    throw ShapeCompileException("shnex:conformsToShape of node expression $node must have exactly two arguments ( node shape )")
                }
                NodeExpression.ConformsToShape(args[0], args[1])
            }
            function.value.startsWith(ShaclSparqlTerms.SPARQL_NAMESPACE) ->
                NodeExpression.SparqlCall(function.value.removePrefix(ShaclSparqlTerms.SPARQL_NAMESPACE), args)
            else -> NodeExpression.CustomList(function, args)
        }
    }

    private companion object {
        val BOOLEAN_LEXICALS = setOf("true", "false", "1", "0")

        /** The xsd:integer lexical space after whitespace collapsing: ASCII digits only. */
        val INTEGER_LEXICAL = Regex("[+-]?[0-9]+")

        /** XSD whitespace (`#x20 | #x9 | #xD | #xA`), which the `collapse` facet of xsd:integer removes at both ends. */
        val XSD_WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')

        /** The xsd:integer value of [term], or null when it is not a valid xsd:integer literal. */
        fun integer(term: RdfTerm): BigInteger? {
            val literal = term as? Literal ?: return null
            if (literal.datatype != XSD.integer) return null
            val lexical = literal.lexical.trim(*XSD_WHITESPACE)
            if (!INTEGER_LEXICAL.matches(lexical)) return null
            return lexical.removePrefix("+").toBigInteger()
        }

        fun clamp(n: BigInteger): Int = n.max(BigInteger.valueOf(Int.MIN_VALUE.toLong())).min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()

        fun name(p: Iri): String =
            when {
                p.value.startsWith(ShaclSparqlTerms.SHNEX_NAMESPACE) -> "shnex:" + p.value.removePrefix(ShaclSparqlTerms.SHNEX_NAMESPACE)
                p.value.startsWith(ShaclSparqlTerms.SPARQL_NAMESPACE) -> "sparql:" + p.value.removePrefix(ShaclSparqlTerms.SPARQL_NAMESPACE)
                else -> "<${p.value}>"
            }
    }
}

/** The `shnex:` terms of SHACL 1.2 Node Expressions §4 and §6.3. */
internal object NodeExpressionVocabulary {
    private fun term(local: String) = Iri(ShaclSparqlTerms.SHNEX_NAMESPACE + local)

    val `var` = term("var")
    val pathValues = term("pathValues")
    val focusNode = term("focusNode")
    val exists = term("exists")
    val `if` = term("if")
    val then = term("then")
    val `else` = term("else")
    val distinct = term("distinct")
    val intersection = term("intersection")
    val concat = term("concat")
    val remove = term("remove")
    val nodes = term("nodes")
    val filterShape = term("filterShape")
    val limit = term("limit")
    val offset = term("offset")
    val orderBy = term("orderBy")
    val desc = term("desc")
    val flatMap = term("flatMap")
    val findFirst = term("findFirst")
    val matchAll = term("matchAll")
    val count = term("count")
    val min = term("min")
    val max = term("max")
    val sum = term("sum")
    val instancesOf = term("instancesOf")
    val nodesMatching = term("nodesMatching")
    val conformsToShape = term("conformsToShape")
    val arg = term("arg")

    /** The key parameter of each built-in named parameter function, with its other properties (the §4 tables). */
    val BUILTIN: Map<Iri, Set<Iri>> = mapOf(
        `var` to emptySet(),
        pathValues to setOf(focusNode),
        exists to emptySet(),
        `if` to setOf(then, `else`),
        distinct to emptySet(),
        intersection to emptySet(),
        concat to emptySet(),
        remove to setOf(nodes),
        filterShape to setOf(nodes),
        limit to setOf(nodes),
        offset to setOf(nodes),
        orderBy to setOf(nodes, desc),
        flatMap to setOf(nodes),
        findFirst to setOf(nodes),
        matchAll to setOf(nodes),
        count to emptySet(),
        min to emptySet(),
        max to emptySet(),
        sum to emptySet(),
        instancesOf to emptySet(),
        nodesMatching to emptySet(),
        arg to emptySet(),
    )
}

/** A custom named parameter function (§6.1): its declared parameter paths, of which [keys] are key parameters. */
internal class NamedParameterFunction(val iri: Iri, val parameters: Set<Iri>, val keys: Set<Iri>)

/**
 * The custom named parameter functions of a shapes graph (§6.1), by key parameter: the SHACL instances of
 * `sh:NamedParameterExpressionFunction`. Built once per [ShapeGraphIndex]; an ill-formed declaration (a parameter
 * without exactly one IRI `sh:path`, no key parameter, a key parameter shared with another function) throws
 * [ShapeCompileException]. `sh:bodyExpression` is compiled where the function is evaluated.
 */
internal class NodeExpressionFunctions private constructor(val byKey: Map<Iri, NamedParameterFunction>) {
    companion object {
        fun of(index: ShapeGraphIndex): NodeExpressionFunctions = index.nodeExpressionFunctions

        fun build(index: ShapeGraphIndex): NodeExpressionFunctions {
            val candidates = LinkedHashSet<RdfResource>()
            for (t in index.triples) {
                index.tick("node expression function compilation")
                if (t.predicate == RDF.type) candidates.add(t.subject)
            }
            val byKey = LinkedHashMap<Iri, NamedParameterFunction>()
            for (candidate in candidates) {
                index.tick("node expression function compilation")
                if (!index.isInstanceOf(candidate, ShaclSparqlTerms.NamedParameterExpressionFunction)) continue
                val function = declaration(candidate, index)
                for (key in function.keys) {
                    val clash = byKey[key]?.iri?.let { "custom function $it" } ?: builtinClash(key, index)
                    if (clash != null) {
                        throw ShapeCompileException("Key parameter <${key.value}> of node expression function ${function.iri} is also a key parameter of $clash")
                    }
                    byKey[key] = function
                }
            }
            return NodeExpressionFunctions(byKey)
        }

        private fun declaration(node: RdfResource, index: ShapeGraphIndex): NamedParameterFunction {
            val iri = node as? Iri ?: throw ShapeCompileException("Node expression function $node must be an IRI")
            val parameters = LinkedHashSet<Iri>()
            val keys = LinkedHashSet<Iri>()
            for (parameter in index.objects(iri, SHACL.parameter)) {
                index.tick("node expression function compilation")
                val p = parameter as? RdfResource
                    ?: throw ShapeCompileException("Parameter $parameter of node expression function $iri must be a resource")
                val path = index.objects(p, SHACL.path).singleOrNull() as? Iri
                    ?: throw ShapeCompileException("Parameter $p of node expression function $iri must have exactly one IRI sh:path")
                if (!parameters.add(path)) {
                    throw ShapeCompileException("Node expression function $iri declares the parameter <${path.value}> more than once")
                }
                val key = index.objects(p, ShaclSparqlTerms.keyParameter)
                if (key.size > 1 || key.any { it !is Literal || it.datatype != XSD.boolean }) {
                    throw ShapeCompileException("Parameter $p of node expression function $iri must have at most one xsd:boolean sh:keyParameter")
                }
                if (key.any { isLexicallyTrue(it) }) keys.add(path)
            }
            if (keys.isEmpty()) throw ShapeCompileException("Node expression function $iri must declare at least one parameter with sh:keyParameter true")
            return NamedParameterFunction(iri, parameters, keys)
        }

        /** What else uses [key] as its key parameter or list parameter property, or null. */
        private fun builtinClash(key: Iri, index: ShapeGraphIndex): String? =
            when {
                key == RDF.first || key == RDF.rest -> "list expressions"
                key.value.startsWith(ShaclSparqlTerms.SHNEX_NAMESPACE) -> "the built-in node expression functions"
                key.value.startsWith(ShaclSparqlTerms.SPARQL_NAMESPACE) -> "the SPARQL function expressions"
                index.isInstanceOf(key, ShaclSparqlTerms.ListParameterExpressionFunction) -> "custom list parameter function $key"
                else -> null
            }
    }
}
