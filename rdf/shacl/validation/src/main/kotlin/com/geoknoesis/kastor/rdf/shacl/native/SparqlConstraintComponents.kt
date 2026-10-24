package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * A parameter of a SPARQL-based constraint component whose value [node] is the node expression [expr] (SHACL 1.2 Node
 * Expressions §8), allowed because the parameter's [declaration] rules out blank nodes. The one output node per focus
 * node must not be a blank node and must match every constant `sh:nodeKind` ([nodeKinds]: the alternatives of each)
 * and `sh:datatype` ([datatypes]: the alternatives of each) of the declaration.
 */
internal data class ComputedComponentParameter(
    val node: BlankNode,
    val expr: NodeExpression,
    val path: Iri,
    val declaration: RdfResource,
    val nodeKinds: List<List<Iri>>,
    val datatypes: List<List<Iri>>,
)

/**
 * The SPARQL-based constraint components of a shapes graph (SHACL 1.2 SPARQL §4.2): IRIs outside the `sh:`
 * namespace (SHACL Core components are built in) that are SHACL instances of `sh:ConstraintComponent`.
 *
 * A shape uses a component when it has values for every mandatory parameter (any parameter, when all are optional).
 * Declarations are checked lazily: a component only when a compiled shape uses it, a validator only when it is the
 * one selected for a shape kind that occurs. Ill-formed declarations raise [ShapeCompileException].
 */
internal class SparqlComponentCatalog private constructor(
    private val index: ShapeGraphIndex,
    /** Components by the paths of their parameters, so a shape only looks at components it can use. */
    private val byPath: Map<Iri, List<Component>>,
) {
    /** Lenient view of a parameter declaration, enough to decide whether a shape uses the component. */
    private class LenientParameter(val node: RdfResource, val paths: List<Iri>, val optional: Boolean)

    /**
     * A checked parameter declaration [node]. [types] tells whether its constant Core constraints rule out blank nodes,
     * so that a blank-node value is a node expression (SHACL 1.2 Node Expressions §8), and how to check a computed value.
     */
    private class Parameter(val name: String, val path: Iri, val optional: Boolean, val node: RdfResource, val types: DeclaredTypes)

    /**
     * What the constant Core constraints of a parameter declaration say about its values (SHACL 1.2 SPARQL §4.2.1: a
     * parameter is a property shape, and shapes whose values break it are ill-formed). [blankExcluding] when every
     * blank node breaks one of them, so a blank-node value cannot be a constant. [nodeKinds] and [datatypes] are the
     * alternatives of each constant `sh:nodeKind` and `sh:datatype`, which a computed value must match.
     */
    private class DeclaredTypes(val blankExcluding: Boolean, val nodeKinds: List<List<Iri>>, val datatypes: List<List<Iri>>)

    /** A selected, well-formed validator; [text] has no `$PATH` substitution yet. */
    private inner class Validator(val node: RdfResource, val ask: Boolean, val text: String) {
        val messages = index.objects(node, SHACL.message).filterIsInstance<Literal>()
        val prologue = SparqlPrefixes.prologue(SparqlPrefixes.collect(node, index))
        val annotations = compileResultAnnotations(node, index)
    }

    private inner class Component(val iri: Iri, val declared: List<LenientParameter>) {
        private val declarationNodes = declared.mapTo(HashSet()) { it.node }
        private val mandatory = declared.filter { !it.optional }
        val parameters: List<Parameter> by lazy { checkedParameters(this) }
        private val validators = HashMap<Boolean, Validator?>()

        fun usedBy(shape: RdfResource): Boolean {
            if (shape in declarationNodes) return false
            fun hasValue(p: LenientParameter) = p.paths.any { index.objects(shape, it).isNotEmpty() }
            return if (mandatory.isEmpty()) declared.any(::hasValue) else mandatory.all(::hasValue)
        }

        fun validator(propertyShape: Boolean): Validator? = validators.getOrPut(propertyShape) { selectValidator(iri, propertyShape) }
    }

    /** Whether [predicate] is the path of a parameter of one of the components. */
    fun isParameterPath(predicate: Iri): Boolean = predicate in byPath

    private val dynamicPaths: Set<Iri> by lazy {
        byPath.values.flatten().distinct().flatMap { it.declared }
            .filter { declaredTypes(it.node).blankExcluding }
            .flatMapTo(LinkedHashSet()) { it.paths }
    }

    /**
     * The paths of the parameters of every component whose declaration rules out blank nodes ([DeclaredTypes.blankExcluding]):
     * a blank-node value there is a node expression (SHACL 1.2 Node Expressions §8).
     */
    fun dynamicParameterPaths(): Set<Iri> = dynamicPaths

    private val declaredTypesByNode = HashMap<RdfResource, DeclaredTypes>()

    private fun declaredTypes(node: RdfResource): DeclaredTypes = declaredTypesByNode.getOrPut(node) { readDeclaredTypes(node) }

    /**
     * The [DeclaredTypes] of the parameter declaration [node], from its constant values (D10): an IRI or a literal, or a
     * blank node that is an RDF list. A value that is itself a node expression, or an ill-formed list, says nothing.
     * Blank nodes are ruled out, by Core 1.2's definitions, by `sh:nodeKind` without a blank-node kind (§7.1.3),
     * `sh:datatype` (§7.1.2), `sh:in` without a blank-node member (§7.9.3), `sh:pattern` (§7.4.3), `sh:minLength`
     * and `sh:maxLength` (§7.4.1, §7.4.2), the bounds (§7.3), `sh:languageIn` (§7.4.5) and `sh:rootClass` (§7.9.4).
     * Not by `sh:class`: a SHACL instance may be a blank node (§7.1.1).
     */
    private fun readDeclaredTypes(node: RdfResource): DeclaredTypes {
        fun isList(term: BlankNode) = index.objects(term, RDF.first).isNotEmpty() || index.objects(term, RDF.rest).isNotEmpty()
        fun constants(p: Iri) = index.objects(node, p).filter { it !is BlankNode || isList(it) }
        fun members(v: RdfTerm): List<RdfTerm>? = when {
            v == RDF.nil -> emptyList()
            v is BlankNode -> try { index.parseRdfList(v) } catch (_: ShapeCompileException) { null }
            else -> null
        }
        fun iris(v: RdfTerm): List<Iri>? =
            if (v is Iri && v != RDF.nil) listOf(v) else members(v)?.takeIf { m -> m.all { it is Iri } }?.map { it as Iri }
        val nodeKinds = constants(SHACL.nodeKind).mapNotNull(::iris)
        val datatypes = constants(SHACL.datatype).mapNotNull(::iris)
        val blankExcluding =
            nodeKinds.any { kinds -> kinds.all { it in NON_BLANK_KINDS } } ||
                datatypes.isNotEmpty() ||
                constants(SHACL.`in`).any { v -> members(v)?.none { it is BlankNode } == true } ||
                LITERAL_ONLY.any { p -> constants(p).any { it is Literal } } ||
                constants(SHACL.languageIn).any { members(it) != null } ||
                constants(SHACL.rootClass).any { iris(it) != null }
        return DeclaredTypes(blankExcluding, nodeKinds, datatypes)
    }

    /**
     * The uses of components by [shape]. A value for which [ignored] is true (an unsupported node expression ignored with
     * a warning) leaves out the constraints that take it.
     */
    fun constraintsFor(
        shape: RdfResource,
        propertyShape: Boolean,
        path: ShaclPath?,
        ignored: (Iri, RdfTerm) -> Boolean = { _, _ -> false },
    ): List<PropertyConstraint.SparqlComponent> {
        val candidates = index.predicates(shape).flatMapTo(LinkedHashSet()) { byPath[it].orEmpty() }
        if (candidates.isEmpty()) return emptyList()
        val out = ArrayList<PropertyConstraint.SparqlComponent>()
        for (component in candidates.sortedBy { it.iri.value }) {
            index.tick("SPARQL constraint components")
            if (!component.usedBy(shape)) continue
            val parameters = component.parameters
            val validator = component.validator(propertyShape) ?: continue
            out.addAll(compile(component, parameters, validator, shape, path, ignored))
        }
        return out
    }

    private fun compile(
        component: Component,
        parameters: List<Parameter>,
        validator: Validator,
        shape: RdfResource,
        path: ShaclPath?,
        ignored: (Iri, RdfTerm) -> Boolean,
    ): List<PropertyConstraint.SparqlComponent> {
        val preBound = LinkedHashSet<String>().apply {
            add("this")
            parameters.forEach { add(it.name) }
            if (validator.ask) add("value")
        }
        val body = if (validator.ask) {
            SparqlQueryTemplate.askAsSelect(validator.text)
        } else {
            SparqlQueryTemplate.substitutePath(validator.text, path?.let { { ShapesCompiler.renderSparqlPath(it) } })
        }
        val template = SparqlQueryTemplate.compileRestricted(
            validator.prologue, body, preBound, "Validator ${validator.node} of constraint component ${component.iri}",
        )
        val messages = validator.messages.ifEmpty { index.objects(component.iri, SHACL.message).filterIsInstance<Literal>() }
        // One constraint per combination of the values of the parameters the shape has; absent optional ones stay unbound.
        // The number of combinations is checked before any is built: memory, not only time, must stay bounded.
        var size = 1L
        for (parameter in parameters) {
            val count = index.objects(shape, parameter.path).size.toLong()
            // Saturating multiplication: a product beyond Long.MAX_VALUE stays Long.MAX_VALUE.
            if (count > 0) size = if (size > Long.MAX_VALUE / count) Long.MAX_VALUE else size * count
        }
        if (size > MAX_CONSTRAINTS_PER_COMPONENT_USE) {
            throw ShapeCompileException(
                "Shape $shape uses constraint component ${component.iri} with $size combinations of parameter values; " +
                    "at most $MAX_CONSTRAINTS_PER_COMPONENT_USE are supported",
            )
        }
        var combinations = listOf(emptyMap<String, ComponentValue>())
        for (parameter in parameters) {
            val values = index.objects(shape, parameter.path)
            if (values.isEmpty()) continue
            val classified = values.map { componentValue(component, parameter, shape, it, ignored) }
            combinations = combinations.flatMap { partial ->
                classified.map { value -> index.tick("SPARQL constraint components"); partial + (parameter.name to value) }
            }
        }
        val paths = parameters.associate { it.name to it.path }
        // The constraints that use an ignored value are left out.
        return combinations.filter { values -> values.values.none { it is ComponentValue.Ignored } }.map { values ->
            PropertyConstraint.SparqlComponent(
                component.iri, validator.node, validator.ask, template,
                values.mapNotNull { (name, value) -> (value as? ComponentValue.Constant)?.let { name to it.term } }.toMap(),
                messages, validator.annotations,
                values.map { (name, value) -> paths.getValue(name) to value.term },
                values.mapNotNull { (name, value) -> (value as? ComponentValue.Computed)?.let { name to it.parameter } }.toMap(),
            )
        }
    }

    /** The value [term] of a parameter in a use of a component: a constant, a node expression, or an ignored unsupported one. */
    private sealed class ComponentValue(val term: RdfTerm) {
        class Constant(term: RdfTerm) : ComponentValue(term)
        class Computed(val parameter: ComputedComponentParameter) : ComponentValue(parameter.node)
        class Ignored(term: RdfTerm) : ComponentValue(term)
    }

    /**
     * The [value] of [parameter] for [shape]: a blank node is a node expression when the declaration rules out blank
     * nodes (SHACL 1.2 Node Expressions §8; D10), and a constant otherwise, as is any other term.
     */
    private fun componentValue(
        component: Component,
        parameter: Parameter,
        shape: RdfResource,
        value: RdfTerm,
        ignored: (Iri, RdfTerm) -> Boolean,
    ): ComponentValue {
        if (value !is BlankNode || !parameter.types.blankExcluding) return ComponentValue.Constant(value)
        if (ignored(parameter.path, value)) return ComponentValue.Ignored(value)
        val expr = NodeExpressions.compile(value, index)
            ?: throw ShapeCompileException(
                "<${parameter.path.value}> value $value of shape $shape is neither a constant nor a node expression; parameter " +
                    "${parameter.node} of constraint component ${component.iri} rules out blank nodes (SHACL 1.2 SPARQL §4.2.1)",
            )
        return ComponentValue.Computed(
            ComputedComponentParameter(value, expr, parameter.path, parameter.node, parameter.types.nodeKinds, parameter.types.datatypes),
        )
    }

    private fun checkedParameters(component: Component): List<Parameter> {
        val parameters = component.declared.map { declared ->
            val node = declared.node
            val path = index.objects(node, SHACL.path).singleOrNull() as? Iri
                ?: throw ShapeCompileException("Parameter $node of constraint component ${component.iri} must have exactly one IRI sh:path")
            val optional = index.objects(node, ShaclSparqlTerms.optional)
            if (optional.size > 1 || optional.any { it !is Literal || it.datatype != XSD.boolean }) {
                throw ShapeCompileException("Parameter $node of constraint component ${component.iri} must have at most one xsd:boolean sh:optional")
            }
            val name = SparqlQueryTemplate.localName(path)
            if (!SparqlQueryTemplate.isVarName(name) || name in RESERVED) {
                throw ShapeCompileException("Parameter <${path.value}> of constraint component ${component.iri} has the unusable name '$name'")
            }
            Parameter(name, path, optional.any { isLexicallyTrue(it) }, node, declaredTypes(node))
        }
        parameters.groupBy { it.name }.forEach { (name, same) ->
            if (same.size > 1) throw ShapeCompileException("Constraint component ${component.iri} has several parameters named '$name'")
        }
        if (parameters.all { it.optional }) {
            throw ShapeCompileException("Constraint component ${component.iri} must have at least one mandatory parameter")
        }
        return parameters
    }

    /**
     * The first SPARQL validator (by IRI, then blank node id) for the shape kind: `sh:propertyValidator` /
     * `sh:nodeValidator` SELECT validators, then `sh:validator` ASK validators. Others (e.g. non-SPARQL validators)
     * do not fit and are skipped.
     */
    private fun selectValidator(component: Iri, propertyShape: Boolean): Validator? {
        val selectPredicate = if (propertyShape) ShaclSparqlTerms.propertyValidator else ShaclSparqlTerms.nodeValidator
        fun fitting(predicate: Iri, cls: Iri) =
            index.objects(component, predicate).filterIsInstance<RdfResource>()
                .filter { index.isInstanceOf(it, cls) }
                .sortedWith(ShapesCompiler.resourceOrdering)
                .firstOrNull()
        fitting(selectPredicate, ShaclSparqlTerms.SPARQLSelectValidator)?.let { node ->
            val text = queryText(node, SHACL.select, component)
            val projected = SparqlQueryTemplate.projectedVariables(text)
            val projectsThis = projected?.contains("this") ?: ("this" in SparqlQueryTemplate.compile(text, setOf("this")).preBound)
            if (!projectsThis) throw ShapeCompileException("SELECT validator $node of constraint component $component must project \$this")
            return Validator(node, ask = false, text)
        }
        return fitting(ShaclSparqlTerms.validator, ShaclSparqlTerms.SPARQLAskValidator)?.let { node ->
            val text = queryText(node, SHACL.ask, component)
            // askAsSelect leaves a non-ASK query unchanged, so the query form is checked here (spec 4.2.3.2).
            val form = SparqlLexer.tokenize(text).firstOrNull { t -> QUERY_FORMS.any { t.isWord(it) } }
            if (form == null || !form.isWord("ASK")) {
                throw ShapeCompileException("ASK validator $node of constraint component $component must hold a SPARQL ASK query")
            }
            Validator(node, ask = true, text)
        }
    }

    private fun queryText(validator: RdfResource, predicate: Iri, component: Iri): String {
        val text = index.objects(validator, predicate).singleOrNull() as? Literal
        if (text == null || text is LangString || text.datatype != XSD.string) {
            throw ShapeCompileException("Validator $validator of constraint component $component must have exactly one xsd:string $predicate")
        }
        return text.lexical
    }

    companion object {
        /** Variable names a parameter cannot take (SHACL 1.2 SPARQL §4.2.1). */
        private val RESERVED = setOf("this", "path", "PATH", "value", "message", "failure")

        /** SPARQL query-form keywords; the first one in an `sh:ask` text must be `ASK`. */
        private val QUERY_FORMS = listOf("SELECT", "CONSTRUCT", "DESCRIBE", "ASK")

        /** The node kinds no blank node has (Core 1.2 §7.1.3). */
        private val NON_BLANK_KINDS = setOf(SHACL.IRI, SHACL.Literal, SHACL.IRIOrLiteral, SHACL.TripleTerm)

        /** Declaration constraints whose literal value every blank node breaks (Core 1.2 §7.3, §7.4.1–§7.4.3). */
        private val LITERAL_ONLY = listOf(
            SHACL.pattern, SHACL.minLength, SHACL.maxLength,
            SHACL.minInclusive, SHACL.maxInclusive, SHACL.minExclusive, SHACL.maxExclusive,
        )

        /**
         * Upper bound on the constraints one shape gets from one component: the product of the numbers of values of
         * its parameters. A hostile shapes graph of a few hundred triples could otherwise request ~10^9 combinations
         * and exhaust memory long before the preparation timeout. A use above the bound is a [ShapeCompileException].
         */
        private const val MAX_CONSTRAINTS_PER_COMPONENT_USE = 1024L

        fun build(index: ShapeGraphIndex): SparqlComponentCatalog {
            val typed = LinkedHashSet<Iri>()
            for (t in index.triples) {
                index.tick("SPARQL constraint components")
                val subject = t.subject
                if (t.predicate == RDF.type && subject is Iri && !subject.value.startsWith(SHACL.namespace)) typed.add(subject)
            }
            val byPath = HashMap<Iri, MutableList<Component>>()
            val catalog = SparqlComponentCatalog(index, byPath)
            for (iri in typed) {
                index.tick("SPARQL constraint components")
                if (!index.isInstanceOf(iri, SHACL.ConstraintComponent)) continue
                val declared = index.objects(iri, SHACL.parameter).filterIsInstance<RdfResource>().map { node ->
                    index.tick("SPARQL constraint components")
                    LenientParameter(
                        node,
                        index.objects(node, SHACL.path).filterIsInstance<Iri>(),
                        index.objects(node, ShaclSparqlTerms.optional).any { isLexicallyTrue(it) },
                    )
                }
                val component = catalog.Component(iri, declared)
                declared.flatMapTo(LinkedHashSet()) { it.paths }.forEach { byPath.getOrPut(it) { ArrayList() }.add(component) }
            }
            return catalog
        }
    }
}
