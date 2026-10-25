package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.reifierDeactivation
import com.geoknoesis.kastor.rdf.shacl.native.ShapeFeatureDetection.isUnsupportedParameterExpression
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.ensureShapeTermAllowed
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.parseSeverityValues
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.shExpression
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintCompilation.compileSparqlConstraint

internal enum class ConstraintFlavor {
    PROPERTY_SHAPE,
    NODE_SCALAR,
}

/**
 * Compiles the constraint parameters of [subject].
 *
 * Parameters SHACL allows to repeat (`sh:pattern`, `sh:hasValue`, `sh:equals`, `sh:disjoint`, `sh:lessThan`,
 * `sh:lessThanOrEquals`, value range bounds, `sh:qualifiedValueShape`, `sh:class`, `sh:datatype`, nested shape
 * references…) yield one constraint per value. Parameters restricted to at most one value (`sh:minCount`,
 * `sh:maxCount`, `sh:minLength`, `sh:maxLength`, `sh:flags`, `sh:in`, `sh:languageIn`, `sh:uniqueLang`,
 * `sh:qualifiedMinCount`, `sh:qualifiedMaxCount`, `sh:qualifiedValueShapesDisjoint`, and the SHACL 1.2 list and
 * boolean parameters) raise [ShapeCompileException] when repeated.
 */
internal class ConstraintCompiler(
    private val subject: RdfResource,
    private val index: ShapeGraphIndex,
    private val config: ValidationConfig,
    private val flavor: ConstraintFlavor,
    private val path: ShaclPath?,
) {
    private val constraints = mutableListOf<PropertyConstraint>()
    private val propertyShape = flavor == ConstraintFlavor.PROPERTY_SHAPE

    fun compile(): MutableList<PropertyConstraint> {
        if (propertyShape) {
            atMostOne(SHACL.minCount)?.let { scalar(DynamicComponent.MIN_COUNT, SHACL.minCount, it) }
            atMostOne(SHACL.maxCount)?.let { scalar(DynamicComponent.MAX_COUNT, SHACL.maxCount, it) }
        }
        valueTypes()
        patterns()
        lengthsAndLists()
        references()
        expressions()
        pathsAndRanges()
        if (propertyShape) {
            qualified()
            reification()
        }
        sparql()
        return constraints
    }

    private fun atMostOne(pred: Iri): RdfTerm? {
        val values = index.objects(subject, pred)
        if (values.size > 1) {
            throw ShapeCompileException("Shape $subject has ${values.size} values for $pred; SHACL allows at most one")
        }
        return values.firstOrNull()
    }

    // SHACL 1.2 Core §3.1.6: a reifier of one of the parameter triples a constraint uses deactivates it, for every
    // focus node (the constant true: the constraint is left out) or per focus node (Deactivatable).
    private fun add(c: PropertyConstraint, vararg triples: Pair<Iri, RdfTerm?>) {
        val conditions = reifierDeactivation(subject, triples.mapNotNull { (p, o) -> o?.let { p to it } }, index) ?: return
        constraints.add(if (conditions.isEmpty()) c else PropertyConstraint.Deactivatable(c, conditions))
    }

    private fun valueOf(pred: Iri, term: RdfTerm): ParameterValue? = parameterValue(subject, pred, term, index, config)

    // A component with several parameters, from their [values] (absent ones are not keys): the constant constraint
    // when every value is a constant, else a Dynamic one. [triples] are its parameter triples, for reifier
    // deactivation.
    private fun multi(component: DynamicComponent, values: Map<Iri, ParameterValue>, extras: ComponentExtras, vararg triples: Pair<Iri, RdfTerm?>) {
        val constants = values.mapValues { (_, v) -> (v as? ParameterValue.Constant)?.let { listOf(it.term) } }
        if (constants.values.all { it != null }) {
            ConstraintInstantiation.instantiate(component, constants.mapValues { it.value!! }, extras, config)?.let { add(it, *triples) }
        } else {
            add(PropertyConstraint.Dynamic(component, values, extras), *triples)
        }
    }

    // SHACL 1.2 Node Expressions §8: a constant value is the constant constraint, as ever; a value a node expression
    // computes is a Dynamic one, instantiated per focus node. Both use the parameter triple for reifier deactivation.
    // [members] gives the values of a constant: the term itself for a single-node parameter, the members of a
    // list-taking one.
    private fun parameter(
        component: DynamicComponent,
        pred: Iri,
        term: RdfTerm,
        extras: ComponentExtras = ComponentExtras(),
        members: (RdfTerm) -> List<RdfTerm> = { listOf(it) },
    ) {
        when (val value = valueOf(pred, term) ?: return) {
            is ParameterValue.Constant ->
                ConstraintInstantiation.instantiate(component, mapOf(pred to members(term)), extras, config)?.let { add(it, pred to term) }
            is ParameterValue.Computed -> add(PropertyConstraint.Dynamic(component, mapOf(pred to value), extras), pred to term)
        }
    }

    private fun scalar(component: DynamicComponent, pred: Iri, term: RdfTerm) = parameter(component, pred, term)

    // The members of a constant IRI-or-list value (Core §6.6.3): the IRI, or the members of the list, which
    // instantiate checks are IRIs.
    private fun iriOrList(term: RdfTerm, notIriOrList: () -> String): List<RdfTerm> =
        when (term) {
            is Iri -> listOf(term)
            is BlankNode -> index.parseRdfList(term)
            else -> throw ShapeCompileException(notIriOrList())
        }

    private fun valueTypes() {
        for (dtObj in index.objects(subject, SHACL.datatype)) {
            val override = annotatedConstraintSeverity(subject, SHACL.datatype, dtObj, index)
            val extras = ComponentExtras(override, annotatedConstraintMessages(subject, SHACL.datatype, dtObj, index))
            parameter(DynamicComponent.DATATYPE, SHACL.datatype, dtObj, extras) { iriOrList(it) { "sh:datatype expects an IRI or RDF list, got $it" } }
        }
        for (co in index.objects(subject, SHACL.`class`)) {
            // A literal (or triple term) is not a class: the shapes graph is ill-formed. Dropping the constraint would
            // validate nothing where the author asked for a class.
            parameter(DynamicComponent.CLASS, SHACL.`class`, co) {
                iriOrList(it) { "sh:class of shape $subject expects a class IRI or an RDF list of IRIs, got $it" }
            }
        }
        // Every sh:nodeKind value is its own constraint (all must hold); an RDF list value allows any listed kind.
        for (nk in index.objects(subject, SHACL.nodeKind)) {
            parameter(DynamicComponent.NODE_KIND, SHACL.nodeKind, nk) {
                iriOrList(it) {
                    "sh:nodeKind of shape $subject expects one of the sh:NodeKind IRIs (sh:IRI, sh:BlankNode, " +
                        "sh:Literal, ...) or an RDF list of them, got $it"
                }
            }
        }
    }

    // Each sh:pattern value is one constraint with the shape's sh:flags; either may be computed (Dynamic PATTERN).
    private fun patterns() {
        val flagsTerm = atMostOne(SHACL.flags)
        val flagsValue = flagsTerm?.let { valueOf(SHACL.flags, it) }
        // A constant sh:flags is checked even when the shape has no sh:pattern, as ever.
        (flagsValue as? ParameterValue.Constant)?.let {
            ConstraintInstantiation.literalString(it.term) ?: throw ShapeCompileException("sh:flags must be a string literal")
        }
        if (flagsTerm == null || flagsValue != null) {
            for (p in index.objects(subject, SHACL.pattern)) {
                val patternValue = valueOf(SHACL.pattern, p) ?: continue
                // A constant half of a Dynamic pattern is checked here, with the constant messages, whether or not a
                // focus node is ever reached: constant flags must be supported, and a constant pattern must be a string
                // literal. Whether the pattern compiles depends on the computed flags (q, x): it is compiled per focus
                // node, with the resolved flags.
                if (patternValue is ParameterValue.Constant && flagsValue is ParameterValue.Computed) {
                    ConstraintInstantiation.literalString(patternValue.term) ?: throw ShapeCompileException("sh:pattern must be a string literal")
                }
                if (patternValue is ParameterValue.Computed && flagsValue is ParameterValue.Constant) {
                    compileShaclPattern("", ConstraintInstantiation.literalString(flagsValue.term))
                }
                val values = listOfNotNull(SHACL.pattern to patternValue, flagsValue?.let { SHACL.flags to it }).toMap()
                // sh:flags is a parameter of each pattern constraint, so its reifier deactivates all of them.
                multi(DynamicComponent.PATTERN, values, ComponentExtras(), SHACL.pattern to p, SHACL.flags to flagsTerm)
            }
        }
    }

    private fun lengthsAndLists() {
        atMostOne(SHACL.minLength)?.let { scalar(DynamicComponent.MIN_LENGTH, SHACL.minLength, it) }
        atMostOne(SHACL.maxLength)?.let { scalar(DynamicComponent.MAX_LENGTH, SHACL.maxLength, it) }
        atMostOne(SHACL.`in`)?.let { head -> parameter(DynamicComponent.IN, SHACL.`in`, head) { index.parseRdfList(it) } }
        for (hv in index.objects(subject, SHACL.hasValue)) {
            ensureShapeTermAllowed(hv, config)
            add(PropertyConstraint.HasValue(hv), SHACL.hasValue to hv)
        }
        atMostOne(SHACL.languageIn)?.let { head -> parameter(DynamicComponent.LANGUAGE_IN, SHACL.languageIn, head) { index.parseRdfList(it) } }
        atMostOne(SHACL.uniqueLang)?.let { scalar(DynamicComponent.UNIQUE_LANG, SHACL.uniqueLang, it) }
        atMostOne(SHACL.minListLength)?.let { scalar(DynamicComponent.MIN_LIST_LENGTH, SHACL.minListLength, it) }
        atMostOne(SHACL.maxListLength)?.let { scalar(DynamicComponent.MAX_LIST_LENGTH, SHACL.maxListLength, it) }
        index.objects(subject, SHACL.memberShape).filterIsInstance<RdfResource>().forEach {
            add(PropertyConstraint.MemberShape(it), SHACL.memberShape to it)
        }
        atMostOne(SHACL.uniqueMembers)?.let { scalar(DynamicComponent.UNIQUE_MEMBERS, SHACL.uniqueMembers, it) }
        atMostOne(SHACL.singleLine)?.let { scalar(DynamicComponent.SINGLE_LINE, SHACL.singleLine, it) }
    }

    private fun references() {
        for (rc in index.objects(subject, SHACL.rootClass)) {
            parameter(DynamicComponent.ROOT_CLASS, SHACL.rootClass, rc) { iriOrList(it) { "sh:rootClass expects an IRI or RDF list" } }
        }
        index.objects(subject, SHACL.subsetOf).forEach {
            add(PropertyConstraint.SubsetOfPath(ShaclPathParser.parse(it, index)), SHACL.subsetOf to it)
        }
        index.objects(subject, SHACL.someValue).filterIsInstance<RdfResource>().forEach {
            add(PropertyConstraint.SomeValue(it), SHACL.someValue to it)
        }
        index.objects(subject, SHACL.shape).filterIsInstance<RdfResource>().forEach {
            add(PropertyConstraint.Shape(it), SHACL.shape to it)
        }
        if (propertyShape) {
            index.objects(subject, SHACL.node).filterIsInstance<RdfResource>().forEach {
                add(PropertyConstraint.Node(it), SHACL.node to it)
            }
            index.objects(subject, SHACL.nodeByExpression).filterIsInstance<Iri>().forEach {
                add(PropertyConstraint.NodeByExpression(it), SHACL.nodeByExpression to it)
            }
            index.objects(subject, SHACL.lessThan).forEach {
                add(PropertyConstraint.LessThanPath(ShaclPathParser.parse(it, index)), SHACL.lessThan to it)
            }
            index.objects(subject, SHACL.lessThanOrEquals).forEach {
                add(PropertyConstraint.LessThanOrEqualsPath(ShaclPathParser.parse(it, index)), SHACL.lessThanOrEquals to it)
            }
        }
    }

    // SHACL 1.2 Node Expressions §7: any shape. A value that is no node expression at all is reported as an
    // unsupported feature (see detectUnsupportedFeatures) and has no constraint.
    private fun expressions() {
        for (term in index.objects(subject, shExpression)) {
            NodeExpressions.compile(term, index)?.let { add(PropertyConstraint.Expression(term, it), shExpression to term) }
        }
        for (term in index.objects(subject, SHACL.nodeByExpression)) {
            if (term is Iri) continue // A constant shape reference: PropertyConstraint.NodeByExpression / nodeByExpressionRefs.
            NodeExpressions.compile(term, index)?.let { add(PropertyConstraint.NodeByExpressionComputed(term, it), SHACL.nodeByExpression to term) }
        }
    }

    private fun pathsAndRanges() {
        index.objects(subject, SHACL.equals).forEach {
            add(PropertyConstraint.EqualsPath(ShaclPathParser.parse(it, index)), SHACL.equals to it)
        }
        index.objects(subject, SHACL.disjoint).forEach {
            add(PropertyConstraint.DisjointPath(ShaclPathParser.parse(it, index)), SHACL.disjoint to it)
        }
        index.objects(subject, SHACL.minInclusive).forEach { scalar(DynamicComponent.MIN_INCLUSIVE, SHACL.minInclusive, it) }
        index.objects(subject, SHACL.maxInclusive).forEach { scalar(DynamicComponent.MAX_INCLUSIVE, SHACL.maxInclusive, it) }
        index.objects(subject, SHACL.minExclusive).forEach { scalar(DynamicComponent.MIN_EXCLUSIVE, SHACL.minExclusive, it) }
        index.objects(subject, SHACL.maxExclusive).forEach { scalar(DynamicComponent.MAX_EXCLUSIVE, SHACL.maxExclusive, it) }
    }

    private fun qualified() {
        val qualifiedShapes = index.objects(subject, SHACL.qualifiedValueShape).filterIsInstance<RdfResource>()
        val minTerm = atMostOne(SHACL.qualifiedMinCount)
        val maxTerm = atMostOne(SHACL.qualifiedMaxCount)
        val disjointTerm = atMostOne(SHACL.qualifiedValueShapesDisjoint)
        val minValue = minTerm?.let { valueOf(SHACL.qualifiedMinCount, it) }
        val maxValue = maxTerm?.let { valueOf(SHACL.qualifiedMaxCount, it) }
        val disjointValue = disjointTerm?.let { valueOf(SHACL.qualifiedValueShapesDisjoint, it) }
        // Constant counts are checked even when the shape has no sh:qualifiedValueShape, as ever.
        (minValue as? ParameterValue.Constant)?.let { ConstraintInstantiation.parseNonNegativeInt(it.term, "sh:qualifiedMinCount") }
        (maxValue as? ParameterValue.Constant)?.let { ConstraintInstantiation.parseNonNegativeInt(it.term, "sh:qualifiedMaxCount") }
        // The sibling shapes matter when the shapes may be disjoint: a computed sh:qualifiedValueShapesDisjoint, or
        // a constant true.
        val mayBeDisjoint = disjointValue != null && (disjointValue !is ParameterValue.Constant || isLexicallyTrue(disjointValue.term))
        val ignored = (minTerm != null && minValue == null) || (maxTerm != null && maxValue == null) || (disjointTerm != null && disjointValue == null)
        for (qvs in qualifiedShapes.takeUnless { ignored }.orEmpty()) {
            val siblings = if (!mayBeDisjoint) emptyList() else qualifiedSiblings(qvs)
            // sh:QualifiedMinCountConstraintComponent and sh:QualifiedMaxCountConstraintComponent share
            // sh:qualifiedValueShape and sh:qualifiedValueShapesDisjoint: a reifier of either deactivates both.
            // The two stay one constraint unless the reifiers of sh:qualifiedMinCount and sh:qualifiedMaxCount
            // deactivate them differently.
            val shared = arrayOf(SHACL.qualifiedValueShape to qvs, SHACL.qualifiedValueShapesDisjoint to disjointTerm)
            val minOnly = minTerm?.let { reifierDeactivation(subject, listOf(SHACL.qualifiedMinCount to it), index) }
            val maxOnly = maxTerm?.let { reifierDeactivation(subject, listOf(SHACL.qualifiedMaxCount to it), index) }
            val extras = ComponentExtras(qualifiedShape = qvs, siblings = siblings)
            // The values of one constraint: the given counts, and sh:qualifiedValueShapesDisjoint if present.
            fun counts(vararg ps: Pair<Iri, ParameterValue?>): Map<Iri, ParameterValue> =
                (ps.toList() + (SHACL.qualifiedValueShapesDisjoint to disjointValue)).mapNotNull { (p, v) -> v?.let { p to it } }.toMap()
            if (minTerm == null || maxTerm == null || minOnly == maxOnly) {
                multi(
                    DynamicComponent.QUALIFIED, counts(SHACL.qualifiedMinCount to minValue, SHACL.qualifiedMaxCount to maxValue), extras,
                    *shared, SHACL.qualifiedMinCount to minTerm, SHACL.qualifiedMaxCount to maxTerm,
                )
            } else {
                multi(DynamicComponent.QUALIFIED, counts(SHACL.qualifiedMinCount to minValue), extras, *shared, SHACL.qualifiedMinCount to minTerm)
                multi(DynamicComponent.QUALIFIED, counts(SHACL.qualifiedMaxCount to maxValue), extras, *shared, SHACL.qualifiedMaxCount to maxTerm)
            }
        }
    }

    /** The `sh:qualifiedValueShape` values of the sibling property shapes of [subject], other than [qvs]. */
    private fun qualifiedSiblings(qvs: RdfResource): List<RdfResource> =
        index.subjects(SHACL.`property`, subject)
            .flatMap { parent -> index.objects(parent, SHACL.`property`) }
            .filterIsInstance<RdfResource>()
            .flatMap { sibling -> index.objects(sibling, SHACL.qualifiedValueShape) }
            .filterIsInstance<RdfResource>()
            .distinct()
            .filter { it != qvs }

    // sh:reificationRequired is an optional parameter of sh:ReifierShapeConstraintComponent (Core §7.8.5): it is
    // part of the constraint of each sh:reifierShape value, and a constraint of its own only without one.
    private fun reification() {
        val reifierShapes = index.objects(subject, SHACL.reifierShape).filterIsInstance<RdfResource>()
        val requiredTerm = atMostOne(SHACL.reificationRequired)
        val requiredValue = requiredTerm?.let { valueOf(SHACL.reificationRequired, it) }
        if (requiredTerm == null || requiredValue != null) {
            val values = requiredValue?.let { mapOf(SHACL.reificationRequired to it) }.orEmpty()
            if (reifierShapes.isEmpty()) {
                requiredTerm?.let { multi(DynamicComponent.REIFICATION, values, ComponentExtras(), SHACL.reificationRequired to it) }
            }
            reifierShapes.forEach {
                multi(DynamicComponent.REIFICATION, values, ComponentExtras(reifierShape = it), SHACL.reifierShape to it, SHACL.reificationRequired to requiredTerm)
            }
        }
    }

    private fun sparql() {
        index.objects(subject, SHACL.sparql).filterIsInstance<RdfResource>().forEach { node ->
            compileSparqlConstraint(node, index, path)?.let { c -> add(c, SHACL.sparql to node) }
        }
        val ignoredComponentValue = { pred: Iri, term: RdfTerm ->
            config.unsupportedFeatures == UnsupportedFeatureHandling.IGNORE_WITH_WARNING && isUnsupportedParameterExpression(pred, term, index)
        }
        for (c in index.sparqlComponents.constraintsFor(subject, propertyShape, path, ignoredComponentValue)) {
            add(c, *c.parameterTriples.toTypedArray())
        }
    }

    private fun annotatedConstraintSeverity(
        subject: RdfResource,
        pred: Iri,
        obj: RdfTerm,
        index: ShapeGraphIndex,
    ): ViolationSeverity? {
        val claim = RdfTriple(subject, pred, obj)
        for (r in index.reifiersForClaim(claim)) {
            val iris = index.objects(r, SHACL.severity).filterIsInstance<Iri>()
            if (iris.isNotEmpty()) return parseSeverityValues(iris, ViolationSeverity.VIOLATION).level
        }
        return null
    }

    private fun annotatedConstraintMessages(subject: RdfResource, pred: Iri, obj: RdfTerm, index: ShapeGraphIndex): List<Literal> =
        index.reifiersForClaim(RdfTriple(subject, pred, obj)).flatMap { r -> index.objects(r, SHACL.message).filterIsInstance<Literal>() }

    companion object {
        /**
         * The value [term] of the parameter [pred] of [subject] (SHACL 1.2 Node Expressions §8, [DynamicParameters.value]),
         * or null for an unsupported expression (see detectUnsupportedFeatures) ignored with a warning: the constraints that
         * take it are left out.
         */
        fun parameterValue(subject: RdfResource, pred: Iri, term: RdfTerm, index: ShapeGraphIndex, config: ValidationConfig): ParameterValue? =
            if (config.unsupportedFeatures == UnsupportedFeatureHandling.IGNORE_WITH_WARNING && isUnsupportedParameterExpression(pred, term, index)) {
                null
            } else {
                DynamicParameters.value(subject, pred, term, index)
            }
    }
}
