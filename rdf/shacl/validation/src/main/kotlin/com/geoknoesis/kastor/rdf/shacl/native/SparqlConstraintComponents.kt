package com.geoknoesis.kastor.rdf.shacl.native

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

    private class Parameter(val name: String, val path: Iri, val optional: Boolean)

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

    fun constraintsFor(shape: RdfResource, propertyShape: Boolean, path: ShaclPath?): List<PropertyConstraint.SparqlComponent> {
        val candidates = index.predicates(shape).flatMapTo(LinkedHashSet()) { byPath[it].orEmpty() }
        if (candidates.isEmpty()) return emptyList()
        val out = ArrayList<PropertyConstraint.SparqlComponent>()
        for (component in candidates.sortedBy { it.iri.value }) {
            index.tick("SPARQL constraint components")
            if (!component.usedBy(shape)) continue
            val parameters = component.parameters
            val validator = component.validator(propertyShape) ?: continue
            out.addAll(compile(component, parameters, validator, shape, path))
        }
        return out
    }

    private fun compile(
        component: Component,
        parameters: List<Parameter>,
        validator: Validator,
        shape: RdfResource,
        path: ShaclPath?,
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
        var combinations = listOf(emptyMap<String, RdfTerm>())
        for (parameter in parameters) {
            val values = index.objects(shape, parameter.path)
            if (values.isEmpty()) continue
            combinations = combinations.flatMap { partial ->
                values.map { value -> index.tick("SPARQL constraint components"); partial + (parameter.name to value) }
            }
        }
        return combinations.map {
            PropertyConstraint.SparqlComponent(component.iri, validator.node, validator.ask, template, it, messages, validator.annotations)
        }
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
            Parameter(name, path, optional.any { isLexicallyTrue(it) })
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
