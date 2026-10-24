package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

internal data class Targets(
    val targetClasses: List<Iri> = emptyList(),
    val targetNodes: List<RdfTerm> = emptyList(),
    val targetSubjectsOf: List<Iri> = emptyList(),
    val targetObjectsOf: List<Iri> = emptyList(),
    /**
     * Blank node [targetNodes] that have the syntax of a function call `[ fn ( … ) ]` (a single triple whose object
     * is an RDF list), mapped to `fn`, although `fn` is not declared as a function in the shapes graph. Like every
     * blank node target, such a node is a target only when it is a node of the data graph; otherwise it is reported
     * as an unsupported node expression at validation time, and the report names `fn` (see [ShapesCompiler]
     * `isTargetNodeExpression`).
     */
    val callShapedTargets: Map<BlankNode, Iri> = emptyMap(),
    /**
     * The `sh:targetNode` values that are computed node expressions (SHACL 1.2 Core §3.1.3.1): their output nodes for
     * the shape as focus node are targets.
     */
    val targetNodeExpressions: List<NodeExpression> = emptyList(),
)

internal sealed class NodeLogicalPart {
    /**
     * The non-constant `sh:deactivated` values of the reifiers of the part's parameter triple (SHACL 1.2 Core §3.1.6):
     * the part is deactivated for a focus node when one of them produces exactly `true`.
     */
    abstract val deactivatedWhen: List<NodeExpression>

    data class And(val operands: List<RdfResource>, override val deactivatedWhen: List<NodeExpression> = emptyList()) : NodeLogicalPart()
    data class Or(val operands: List<RdfResource>, override val deactivatedWhen: List<NodeExpression> = emptyList()) : NodeLogicalPart()
    data class Xone(val operands: List<RdfResource>, override val deactivatedWhen: List<NodeExpression> = emptyList()) : NodeLogicalPart()
    data class Not(val operand: RdfResource, override val deactivatedWhen: List<NodeExpression> = emptyList()) : NodeLogicalPart()

    fun operandRefs(): List<RdfResource> =
        when (this) {
            is And -> operands
            is Or -> operands
            is Xone -> operands
            is Not -> listOf(operand)
        }
}

internal enum class ClosedMode {
    NONE,
    TRUE,
    BY_TYPES,
}

internal sealed class PropertyConstraint {
    data class MinCount(val n: Int) : PropertyConstraint()
    data class MaxCount(val n: Int) : PropertyConstraint()
    /** Allowed RDF literals match if lexical/datatype fits **any** listed datatype (`sh:datatype` lists). */
    data class Datatype(
        val allowed: List<Iri>,
        val severityOverride: ViolationSeverity? = null,
        /** `sh:message` values annotated on the `sh:datatype` triple (RDF 1.2 reifier), overriding the shape messages. */
        val messages: List<Literal> = emptyList(),
    ) : PropertyConstraint()
    data class Class(val iri: Iri) : PropertyConstraint()
    /** `sh:class` with an RDF list of classes: value must satisfy at least one entry (OR). */
    data class ClassAnyOf(val options: List<Iri>) : PropertyConstraint()
    /** Focus/value satisfies **any** listed node kind (`sh:nodeKind` lists). */
    data class NodeKind(val kinds: List<Iri>) : PropertyConstraint()
    /** One `sh:pattern` value combined with the shape's (single) `sh:flags`, compiled once at shape compile time. */
    class Pattern(val pattern: String, val flags: String?, val regex: Regex) : PropertyConstraint()
    data class MinLength(val n: Int) : PropertyConstraint()
    data class MaxLength(val n: Int) : PropertyConstraint()
    data class In(val allowed: List<RdfTerm>) : PropertyConstraint()
    data class HasValue(val value: RdfTerm) : PropertyConstraint()
    data class LanguageIn(val langs: List<String>) : PropertyConstraint()
    data class UniqueLang(val enabled: Boolean) : PropertyConstraint()
    data class Node(val nestedShape: RdfResource) : PropertyConstraint()
    /** SHACL 1.2 `sh:nodeByExpression` on a property shape whose value is a shape IRI (a constant node expression). */
    data class NodeByExpression(val nestedShape: RdfResource) : PropertyConstraint()
    /**
     * SHACL 1.2 `sh:nodeByExpression` (Node Expressions §7.2) whose value [node] is not a shape IRI: each value node
     * must conform to every output node of [expr] evaluated with the value node as focus node, which must be a shape.
     */
    data class NodeByExpressionComputed(val node: RdfTerm, val expr: NodeExpression) : PropertyConstraint()
    /**
     * SHACL 1.2 `sh:expression` (Node Expressions §7.1): [expr] must produce exactly `( true )` for each value node,
     * evaluated with the focus node and the scope variable `value`. [node] is the `sh:expression` value, reported as
     * `sh:sourceConstraint`; the report RDF holds its deep copy (§7.1, see [com.geoknoesis.kastor.rdf.shacl.providers.withSourceConstraintCopy]).
     */
    data class Expression(val node: RdfTerm, val expr: NodeExpression) : PropertyConstraint()
    data class EqualsPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class DisjointPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class LessThanPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class LessThanOrEqualsPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class MinInclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MaxInclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MinExclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MaxExclusive(val bound: RdfTerm) : PropertyConstraint()
    /**
     * `sh:qualifiedValueShape`. [siblings] are the sibling shapes of SHACL §4.7.3 used when [disjoint] is true: all
     * values of `sh:property/sh:qualifiedValueShape` of the parent shape(s), minus this constraint's own
     * `sh:qualifiedValueShape` value.
     */
    data class Qualified(
        val shape: RdfResource,
        val min: Int?,
        val max: Int?,
        val disjoint: Boolean,
        val siblings: List<RdfResource> = emptyList(),
    ) : PropertyConstraint()
    data class MinListLength(val n: Int) : PropertyConstraint()
    data class MaxListLength(val n: Int) : PropertyConstraint()
    data class MemberShape(val nestedShape: RdfResource) : PropertyConstraint()
    data class UniqueMembers(val enabled: Boolean) : PropertyConstraint()
    data class SubsetOfPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class SingleLine(val enabled: Boolean) : PropertyConstraint()
    data class SomeValue(val nestedShape: RdfResource) : PropertyConstraint()
    data class RootClass(val roots: List<Iri>) : PropertyConstraint()
    data class Shape(val nestedShape: RdfResource) : PropertyConstraint()
    /**
     * SHACL-SPARQL constraint (`sh:sparql`). [template] holds the query with `sh:prefixes` declarations prepended
     * and `$PATH` substituted; pre-binding is applied by the engine through [SparqlQueryTemplate.bind].
     * [deactivatedWhen] is a non-constant `sh:deactivated` of the constraint node (SHACL 1.2 SPARQL, "Validation with
     * SPARQL-based Constraints"): the constraint has no results for a focus node for which
     * `evalExpr(expr, data graph, focus node, {})` produces `true` as its only output node.
     */
    data class Sparql(
        val constraintNode: RdfResource,
        val template: SparqlQueryTemplate,
        val messages: List<Literal>,
        val severity: ViolationSeverity?,
        val severityCustomIri: Iri?,
        val annotations: List<SparqlResultAnnotation>,
        val deactivatedWhen: NodeExpression? = null,
    ) : PropertyConstraint() {
        /** Pre-bound variables the query references (`this`). */
        val preBound: Set<String> get() = template.preBound
    }
    /**
     * One use of a SPARQL-based constraint component (SHACL 1.2 SPARQL §4.2) by a shape: one combination of the
     * shape's [parameters] values (absent optional parameters stay unbound). [template] holds the validator's query
     * with its prefixes prepended and, for SELECT validators of property shapes, `$PATH` substituted; an [ask]
     * validator is rewritten to a SELECT and pre-binds `$value` as well.
     */
    data class SparqlComponent(
        val component: Iri,
        val validator: RdfResource,
        val ask: Boolean,
        val template: SparqlQueryTemplate,
        val parameters: Map<String, RdfTerm>,
        /** The validator's `sh:message` values, else the component's. */
        val messages: List<Literal>,
        val annotations: List<SparqlResultAnnotation>,
        /** The shape's parameter triples (parameter path, value) this combination uses, for reifier deactivation. */
        val parameterTriples: List<Pair<Iri, RdfTerm>> = emptyList(),
    ) : PropertyConstraint()
    /**
     * SHACL 1.2: validate resources that reify triples matching this property shape. [required] is the shape's
     * `sh:reificationRequired`, an optional parameter of the same component (Core §7.8.5): one constraint per
     * `sh:reifierShape` value, each with the requirement.
     */
    data class ReifierShape(val nestedShape: RdfResource, val required: Boolean = false) : PropertyConstraint()
    /**
     * SHACL 1.2: asserted triples matching the path must have at least one `rdf:reifies` reifier. Only for a shape
     * without `sh:reifierShape`; otherwise the requirement is part of each [ReifierShape].
     */
    data class ReificationRequired(val required: Boolean) : PropertyConstraint()
    /**
     * [constraint], which a reifier of one of its parameter triples deactivates per focus node (SHACL 1.2 Core §3.1.6):
     * it is ignored for a focus node for which one of [deactivatedWhen], evaluated with that focus node and an empty
     * scope, produces exactly `true`. Optimisations that read constraints (such as `sh:targetWhere` narrowing) do not
     * see through it, since the constraint does not hold for every node.
     */
    data class Deactivatable(val constraint: PropertyConstraint, val deactivatedWhen: List<NodeExpression>) : PropertyConstraint()

    /** The constraint this one guards, if it is [Deactivatable], else itself. */
    fun unwrapped(): PropertyConstraint = if (this is Deactivatable) constraint else this
}

internal data class CompiledPropertyShape(
    val shapeNode: RdfResource,
    val path: ShaclPath,
    /** The `sh:path` value as written in the shapes graph (IRI or blank node). */
    val pathNode: RdfTerm,
    /** Blank-node structure of a complex [pathNode], reported as `sh:resultPath`. */
    val pathTriples: List<RdfTriple>,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri? = null,
    val messages: List<Literal>,
    val constraints: List<PropertyConstraint>,
    val logicalParts: List<NodeLogicalPart> = emptyList(),
    /** Nested `sh:property` constraints evaluated relative to each value of this shape's path. */
    val nestedPropertyShapes: List<CompiledPropertyShape> = emptyList(),
    /** `sh:deactivated true`: every node conforms. */
    val deactivated: Boolean = false,
    /**
     * A non-constant `sh:deactivated` (SHACL 1.2 Core §3.1.6): the shape is deactivated for a focus node when this
     * expression, evaluated with that focus node and an empty scope, produces exactly `true`.
     */
    val deactivatedWhen: NodeExpression? = null,
    /** `sh:values` node expression (predicate paths only); null when absent or [deactivated]. */
    val values: NodeExpression? = null,
    /** `sh:defaultValue` node expression (predicate paths only); null when absent or [deactivated]. */
    val defaultValue: NodeExpression? = null,
    /** Per focus node deactivation of the shape's `sh:property` triples by reifiers: see [ReifierConditions]. */
    val reifierConditions: ReifierConditions = ReifierConditions.NONE,
)

/**
 * The non-constant `sh:deactivated` values of the reifiers of a shape's parameter triples that are no
 * [PropertyConstraint] (SHACL 1.2 Core §3.1.6): the constraint is deactivated for a focus node, of the shape, when one
 * of its expressions produces exactly `true`. [links] are keyed by the triple's (predicate, object): `sh:property`,
 * `sh:node` and `sh:nodeByExpression` (an IRI) on a node shape, `sh:property` on a property shape. [closed] deactivates
 * `sh:closed` (its `sh:closed` and `sh:ignoredProperties` triples), [uniqueValuesFor] `sh:uniqueValuesFor`.
 */
internal data class ReifierConditions(
    val links: Map<Pair<Iri, RdfResource>, List<NodeExpression>> = emptyMap(),
    val closed: List<NodeExpression> = emptyList(),
    val uniqueValuesFor: List<NodeExpression> = emptyList(),
) {
    fun link(predicate: Iri, target: RdfResource): List<NodeExpression> = links[predicate to target].orEmpty()

    /** Every expression, for the shape closure and the recursion analysis. */
    fun expressions(): List<NodeExpression> = links.values.flatten() + closed + uniqueValuesFor

    companion object {
        val NONE = ReifierConditions()
    }
}

internal data class CompiledNodeShape(
    val shapeNode: RdfResource,
    val targets: Targets,
    /** `sh:targetWhere` shape references (membership = focus conforms to the referenced shape). */
    val targetWhereRefs: List<RdfResource> = emptyList(),
    val propertyShapes: List<CompiledPropertyShape>,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri? = null,
    val messages: List<Literal>,
    val closed: ClosedMode,
    val ignoredProperties: Set<Iri>,
    val logicalParts: List<NodeLogicalPart>,
    /** `sh:node` constraints targeting the focus node itself. */
    val nodeRefs: List<RdfResource>,
    /** `sh:nodeByExpression` shape/expression references (SHACL 1.2). */
    val nodeByExpressionRefs: List<RdfResource> = emptyList(),
    /** Scalar constraints declared on the node shape (e.g. `sh:datatype`, `sh:hasValue`) without `sh:property`. */
    val nodeConstraints: List<PropertyConstraint> = emptyList(),
    /** SHACL 1.2 composite uniqueness across targets (`sh:uniqueValuesFor`). */
    val uniqueValuesForProps: List<Iri> = emptyList(),
    /** `sh:deactivated true`: every node conforms. */
    val deactivated: Boolean = false,
    /** A non-constant `sh:deactivated`: see [CompiledPropertyShape.deactivatedWhen]. */
    val deactivatedWhen: NodeExpression? = null,
    /**
     * Predicates allowed by `sh:closed true` (SHACL §4.8.1): the IRI `sh:path` values of **all** `sh:property`
     * values, including deactivated property shapes.
     */
    val closedAllowedPredicates: Set<Iri> = emptySet(),
    /** Per focus node deactivation of parameter triples by reifiers, other than [nodeConstraints]: see [ReifierConditions]. */
    val reifierConditions: ReifierConditions = ReifierConditions.NONE,
)

/**
 * Compiled shapes graph. Every shape reachable from a top-level shape (via `sh:node`, `sh:and`/`sh:or`/`sh:xone`/
 * `sh:not`, `sh:qualifiedValueShape`, `sh:shape`, `sh:someValue`, `sh:memberShape`, `sh:reifierShape`,
 * `sh:nodeByExpression`, `sh:targetWhere`, `sh:property`, and the shapes of the node expressions at `sh:values`,
 * `sh:defaultValue`, `sh:targetNode`, `sh:expression` and `sh:nodeByExpression` ([NodeExpressions.shapeRefs])) is
 * compiled exactly once — including deactivated and constraint-free shapes, to which every node conforms. So is every
 * candidate of a computed shape reference that compiles ([CompiledShapeGraph.candidateErrors] has the others).
 */
internal data class CompiledShapeGraph(
    /** Top-level, active shapes (targets are evaluated for these). */
    val shapesByNode: Map<RdfResource, CompiledNodeShape>,
    val orderedNodeShapes: List<CompiledNodeShape>,
    val referencedNodeShapes: Map<RdfResource, CompiledNodeShape> = emptyMap(),
    val referencedPropertyShapes: Map<RdfResource, CompiledPropertyShape> = emptyMap(),
    val index: ShapeGraphIndex = ShapeGraphIndex(emptyList()),
    /**
     * Shapes that can reach themselves through shape references (a strongly connected component of the static
     * shape dependency graph, or a self-reference), mapped to their component id. Only these shapes can recurse
     * over data; all other nested checks are bounded by the static nesting depth of the shapes graph.
     */
    val recursiveComponents: Map<RdfResource, Int> = emptyMap(),
    /** Descriptions of unsupported constructs that were ignored ([UnsupportedFeatureHandling.IGNORE_WITH_WARNING]). */
    val unsupportedFeatureWarnings: List<String> = emptyList(),
    /**
     * SHACL 1.2 SPARQL functions declared in the shapes graph ([SparqlFunctionDeclarations]): every SHACL query of a
     * run carries the run's [SparqlFunctionDeclarationSet.library].
     */
    val functions: SparqlFunctionDeclarationSet = SparqlFunctionDeclarationSet.EMPTY,
    /**
     * Shapes that only a computed shape reference can name (R8: the candidates of [ShapesCompiler.ExpressionShapes])
     * and that cannot be compiled, or use an unsupported feature, mapped to the reason. They do not fail the run;
     * validating against one is an evaluation failure.
     */
    val candidateErrors: Map<RdfResource, String> = emptyMap(),
    /**
     * Report warnings about constructs of the compiled shapes that have no effect: the `sh:deactivated` of a reifier
     * of a triple that is no constraint parameter ([ShapesCompiler.isParameterPredicate]). They are no unsupported
     * features.
     */
    val shapeWarnings: List<String> = emptyList(),
)

internal object ShapesCompiler {

    private enum class ConstraintFlavor {
        PROPERTY_SHAPE,
        NODE_SCALAR,
    }

    /**
     * Compiles the shapes of [shapesTriples]. [expressionShapes] names further shapes to compile although nothing in
     * the shapes graph references them: the shapes of a node expression evaluated outside validation (Node
     * Expressions §3.4), as [NodeExpressions.shapeRefs] collects them from the expression compiled over the index.
     */
    fun compile(
        shapesTriples: List<RdfTriple>,
        config: ValidationConfig,
        budget: ValidationBudget = ValidationBudget.NONE,
        expressionShapes: (ShapeGraphIndex) -> Collection<RdfResource> = { emptyList() },
    ): CompiledShapeGraph {
        val index = ShapeGraphIndex(shapesTriples, budget)
        val nodeShapeSubjects = findNodeShapes(index, budget)
        val extraRoots = expressionShapes(index).toList()
        val exprShapes = ExpressionShapes(index)
        // The functions come first, as the shapes of their node expression bodies are validated against (and so
        // inspected) although nothing else may reach them. An ill-formed declaration is reported after detection,
        // which keeps reporting the unsupported features first.
        var functionsError: ShapeCompileException? = null
        val functions = try {
            SparqlFunctionDeclarations.compile(index)
        } catch (e: ShapeCompileException) {
            functionsError = e
            SparqlFunctionDeclarationSet.EMPTY
        }
        val detected = detectUnsupportedFeatures(index, nodeShapeSubjects, extraRoots, functions.expressionBodies, exprShapes, budget)
        val unsupported = detected.strict
        if (unsupported.isNotEmpty() && config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL) {
            throw UnsupportedShaclFeatureException(
                "Unsupported SHACL feature(s) for the native engine: ${unsupported.keys.joinToString("; ")}. " +
                    "Set ValidationConfig.unsupportedFeatures = IGNORE_WITH_WARNING to skip these constructs.",
                unsupported.values.toSet(),
            )
        }
        functionsError?.let { throw it }
        val compiled = mutableListOf<CompiledNodeShape>()
        val byNode = LinkedHashMap<RdfResource, CompiledNodeShape>()
        for (subject in nodeShapeSubjects.sortedWith { a, b -> budget.check("shape ordering"); resourceOrdering.compare(a, b) }) {
            budget.check("shape compilation")
            if (isDeactivated(subject, index)) continue
            val cn = compileNodeShape(subject, index, config)
            compiled.add(cn)
            byNode[subject] = cn
        }

        val refNodes = LinkedHashMap<RdfResource, CompiledNodeShape>(byNode)
        val refProps = LinkedHashMap<RdfResource, CompiledPropertyShape>()
        val candidates = CandidateQueue(exprShapes)
        val referenced = ShapeClosure(index, config, budget, exprShapes, candidates, refNodes, refProps)
        compiled.forEach { referenced.visitNode(it) }
        referenced.add(extraRoots)
        // The shapes of the node expression bodies of the functions SPARQL queries may call.
        functions.expressionBodies.forEach { referenced.addExpression(it) }
        referenced.run()
        val ignoredInCandidates = ArrayList<String>()
        val candidateErrors = compileCandidates(index, config, budget, exprShapes, candidates, refNodes, refProps, detected.candidateOnly, ignoredInCandidates)
        return CompiledShapeGraph(
            byNode, compiled, refNodes, refProps, index,
            recursiveComponents(refNodes, refProps, exprShapes, functions, budget),
            (unsupported.keys + ignoredInCandidates).map { "Unsupported SHACL feature ignored: $it" },
            functions,
            candidateErrors,
            ineffectiveReifierDeactivations(index, refNodes.keys + refProps.keys, budget),
        )
    }

    /**
     * The constraint parameters (SHACL 1.2 Core §3.1.1) of the core components, as predicates of a shape: a reifier of
     * a triple with one of these, or with the path of a parameter of a SPARQL-based component, deactivates the
     * constraints that use the triple (§3.1.6).
     */
    private val coreParameterPredicates: Set<Iri> by lazy {
        setOf(
            SHACL.`class`, SHACL.datatype, SHACL.nodeKind, SHACL.minCount, SHACL.maxCount,
            SHACL.minExclusive, SHACL.minInclusive, SHACL.maxExclusive, SHACL.maxInclusive,
            SHACL.minLength, SHACL.maxLength, SHACL.pattern, SHACL.flags, SHACL.languageIn, SHACL.uniqueLang,
            SHACL.equals, SHACL.disjoint, SHACL.lessThan, SHACL.lessThanOrEquals, SHACL.subsetOf,
            SHACL.`not`, SHACL.`and`, SHACL.`or`, SHACL.xone, SHACL.node, SHACL.nodeByExpression, SHACL.`property`,
            SHACL.qualifiedValueShape, SHACL.qualifiedMinCount, SHACL.qualifiedMaxCount, SHACL.qualifiedValueShapesDisjoint,
            SHACL.closed, SHACL.ignoredProperties, SHACL.hasValue, SHACL.`in`, SHACL.sparql, shExpression,
            SHACL.someValue, SHACL.shape, SHACL.singleLine, SHACL.rootClass, SHACL.memberShape,
            SHACL.minListLength, SHACL.maxListLength, SHACL.uniqueMembers,
            SHACL.reifierShape, SHACL.reificationRequired, SHACL.uniqueValuesFor,
        )
    }

    /**
     * Whether a triple of a shape with [predicate] declares a constraint parameter, so that a reifier's
     * `sh:deactivated` on it deactivates the constraints that use it (SHACL 1.2 Core §3.1.6). On any other triple
     * (targets, `sh:path`, `sh:message`, `sh:severity`, `sh:values`…) it has no effect.
     */
    internal fun isParameterPredicate(predicate: Iri, index: ShapeGraphIndex): Boolean =
        predicate in coreParameterPredicates || index.sparqlComponents.isParameterPath(predicate)

    /**
     * One warning per reifier with `sh:deactivated` of a triple of one of [shapes] that is no constraint parameter
     * ([isParameterPredicate]): the spec gives it no meaning there, so it is ignored.
     */
    private fun ineffectiveReifierDeactivations(index: ShapeGraphIndex, shapes: Collection<RdfResource>, budget: ValidationBudget): List<String> {
        val out = ArrayList<String>()
        for (shape in shapes) {
            for (p in index.predicates(shape)) {
                if (isParameterPredicate(p, index)) continue
                for (o in index.objects(shape, p)) {
                    budget.tick("shape compilation")
                    for (r in index.reifiersForClaim(RdfTriple(shape, p, o))) {
                        if (index.objects(r, SHACL.deactivated).isNotEmpty()) {
                            out.add("sh:deactivated on a reifier of non-parameter triple $shape $p $o has no effect")
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * The candidates of computed shape references that the shapes graph needs ([CandidateQueue]), compiled tolerantly
     * (R8): a candidate whose closure (the shapes it references) does not compile, or uses an unsupported feature
     * ([candidateOnly], under [UnsupportedFeatureHandling.FAIL]), is left out and mapped to the reason, so that an
     * unrelated ill-formed shape fails only an expression that validates against it. A candidate that compiles is
     * added to [refNodes] / [refProps] with its closure; under [UnsupportedFeatureHandling.IGNORE_WITH_WARNING] the
     * unsupported constructs of the shapes added that way are appended to [ignored].
     */
    private fun compileCandidates(
        index: ShapeGraphIndex,
        config: ValidationConfig,
        budget: ValidationBudget,
        exprShapes: ExpressionShapes,
        candidates: CandidateQueue,
        refNodes: MutableMap<RdfResource, CompiledNodeShape>,
        refProps: MutableMap<RdfResource, CompiledPropertyShape>,
        candidateOnly: Map<RdfResource, String>,
        ignored: MutableList<String>,
    ): Map<RdfResource, String> {
        val errors = LinkedHashMap<RdfResource, String>()
        val failOnUnsupported = config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL
        while (candidates.queue.isNotEmpty()) {
            budget.check("shape compilation")
            val candidate = candidates.queue.removeFirst()
            if (candidate in refNodes || candidate in refProps) continue
            val closure = ShapeClosure(
                index, config, budget, exprShapes, candidates, LinkedHashMap(), LinkedHashMap(), refNodes, refProps,
            ) { shape ->
                errors[shape]?.let { throw ShapeCompileException(it) }
                if (failOnUnsupported) {
                    candidateOnly[shape]?.let { throw ShapeCompileException("Shape $shape uses unsupported SHACL feature(s): $it") }
                }
            }
            try {
                closure.add(listOf(candidate))
                closure.run()
            } catch (e: ShapeCompileException) {
                errors[candidate] = "shape $candidate cannot be validated against: ${e.message}"
                continue
            }
            if (!failOnUnsupported) {
                (closure.nodes.keys + closure.props.keys).forEach { shape -> candidateOnly[shape]?.let { ignored.add(it) } }
            }
            closure.nodes.forEach { (k, v) -> refNodes.putIfAbsent(k, v) }
            closure.props.forEach { (k, v) -> refProps.putIfAbsent(k, v) }
        }
        return errors
    }

    /**
     * The candidates of computed shape references ([ExpressionShapes.iriCandidates], [ExpressionShapes.allCandidates]),
     * queued once each when a compiled shape needs them, for [compileCandidates].
     */
    private class CandidateQueue(private val exprShapes: ExpressionShapes) {
        val queue = ArrayDeque<RdfResource>()
        private val seen = HashSet<RdfResource>()
        private var iris = false
        private var all = false

        /** The IRI candidates: a computed `shnex:conformsToShape` shape (§4.5.3) is an IRI. */
        fun wantIris() {
            if (iris || all) return
            iris = true
            exprShapes.iriCandidates.forEach { if (seen.add(it)) queue.add(it) }
        }

        /** Every candidate: the outputs of a computed `sh:nodeByExpression` (§7.2) are IRIs or blank nodes. */
        fun wantAll() {
            if (all) return
            all = true
            exprShapes.allCandidates.forEach { if (seen.add(it)) queue.add(it) }
        }
    }

    /**
     * Compiles the shapes added to it and every shape they reference ([constraintRefs], logical operands, nested
     * property shapes, the shapes of node expressions) into [nodes] / [props], skipping the shapes of [baseNodes] /
     * [baseProps]. [check] runs before a shape is compiled here. A node expression that computes its shapes queues
     * the candidates in [candidates] instead of compiling them.
     */
    private class ShapeClosure(
        private val index: ShapeGraphIndex,
        private val config: ValidationConfig,
        private val budget: ValidationBudget,
        private val exprShapes: ExpressionShapes,
        private val candidates: CandidateQueue,
        val nodes: MutableMap<RdfResource, CompiledNodeShape>,
        val props: MutableMap<RdfResource, CompiledPropertyShape>,
        private val baseNodes: Map<RdfResource, CompiledNodeShape> = emptyMap(),
        private val baseProps: Map<RdfResource, CompiledPropertyShape> = emptyMap(),
        private val check: (RdfResource) -> Unit = {},
    ) {
        private val pending = ArrayDeque<RdfResource>()

        fun add(refs: Collection<RdfResource>) {
            pending.addAll(refs)
        }

        fun addExpression(expr: NodeExpression) {
            pending.addAll(exprShapes.strict(expr))
            if (exprShapes.computes(expr)) candidates.wantIris()
        }

        private fun addConstraint(c: PropertyConstraint) {
            pending.addAll(constraintRefs(c))
            constraintExpressions(c).forEach { addExpression(it) }
            if (c.unwrapped() is PropertyConstraint.NodeByExpressionComputed) candidates.wantAll()
        }

        fun visitProperty(ps: CompiledPropertyShape) {
            if (ps.shapeNode in props || ps.shapeNode in baseProps) return
            check(ps.shapeNode)
            props[ps.shapeNode] = ps
            ps.constraints.forEach { addConstraint(it) }
            ps.logicalParts.forEach { pending.addAll(it.operandRefs()) }
            shapeExpressions(ps).forEach { addExpression(it) }
            ps.nestedPropertyShapes.forEach { visitProperty(it) }
        }

        fun visitNode(cn: CompiledNodeShape) {
            pending.addAll(cn.nodeRefs)
            pending.addAll(cn.nodeByExpressionRefs)
            pending.addAll(cn.targetWhereRefs)
            cn.logicalParts.forEach { pending.addAll(it.operandRefs()) }
            cn.nodeConstraints.forEach { addConstraint(it) }
            cn.targets.targetNodeExpressions.forEach { addExpression(it) }
            shapeExpressions(cn).forEach { addExpression(it) }
            cn.propertyShapes.forEach { visitProperty(it) }
        }

        fun run() {
            while (pending.isNotEmpty()) {
                budget.check("shape compilation")
                val ref = pending.removeFirst()
                if (ref in props || ref in nodes || ref in baseProps || ref in baseNodes) continue
                check(ref)
                if (index.objects(ref, SHACL.path).isNotEmpty()) {
                    visitProperty(compilePropertyShape(ref, index, config))
                } else {
                    val cn = compileNodeShape(ref, index, config)
                    nodes[ref] = cn
                    visitNode(cn)
                }
            }
        }
    }

    /**
     * The shapes a node expression of [index] can validate against, which the compiler must therefore compile: its
     * [NodeExpressions.shapeRefs] ([strict]: compiled like any referenced shape), and, when it computes a
     * `shnex:conformsToShape` shape ([computes]), every IRI of the shapes graph that is a shape ([iriCandidates]),
     * since the computed IRI is only known at evaluation time. A computed `sh:nodeByExpression` can produce any shape,
     * IRI or blank node ([allCandidates]). Candidates are compiled up front, not on demand, so that the compiled graph
     * and its recursion analysis stay complete and immutable, but tolerantly (R8, see [compileCandidates]); the scan
     * is done once per index and ticks the preparation budget, and the compile loop checks the budget per shape.
     */
    internal class ExpressionShapes(val index: ShapeGraphIndex) {
        val iriCandidates: Set<RdfResource> by lazy { shapeCandidates(index, blankNodes = false) }
        val allCandidates: Set<RdfResource> by lazy { shapeCandidates(index, blankNodes = true) }

        fun strict(expr: NodeExpression): Set<RdfResource> = NodeExpressions.shapeRefs(expr, index)

        fun computes(expr: NodeExpression): Boolean = NodeExpressions.computesShapes(expr, index)

        fun of(expr: NodeExpression): Set<RdfResource> = if (computes(expr)) strict(expr) + iriCandidates else strict(expr)

        /**
         * The shapes of the node expressions [ps] evaluates: `sh:values`, `sh:defaultValue`, a non-constant
         * `sh:deactivated`, the reifier conditions of its parameter triples and its constraints'.
         */
        fun of(ps: CompiledPropertyShape): List<RdfResource> =
            (shapeExpressions(ps) + ps.constraints.flatMap { constraintExpressions(it) }).flatMap { of(it) }
    }

    /**
     * The node expressions [ps] evaluates apart from its constraints': `sh:values`, `sh:defaultValue`, a non-constant
     * `sh:deactivated`, and the non-constant `sh:deactivated` of the reifiers of its logical and `sh:property` triples.
     */
    private fun shapeExpressions(ps: CompiledPropertyShape): List<NodeExpression> =
        listOfNotNull(ps.values, ps.defaultValue, ps.deactivatedWhen) + ps.logicalParts.flatMap { it.deactivatedWhen } +
            ps.reifierConditions.expressions()

    /** The node expressions [cn] evaluates apart from its constraints' and its targets' (see the other overload). */
    private fun shapeExpressions(cn: CompiledNodeShape): List<NodeExpression> =
        listOfNotNull(cn.deactivatedWhen) + cn.logicalParts.flatMap { it.deactivatedWhen } + cn.reifierConditions.expressions()

    /** The shapes [expr] of [index] can validate against ([ExpressionShapes]), for expressions evaluated outside validation. */
    fun expressionShapes(expr: NodeExpression, index: ShapeGraphIndex): Set<RdfResource> = ExpressionShapes(index).of(expr)

    private val shapeClasses: Set<RdfTerm> by lazy { setOf(SHACL.NodeShape, SHACL.PropertyShape, SHACL.Shape, SHACL.ShapeClass) }

    private val shapeMarkers: Set<Iri> by lazy {
        (shapeParameters + listOf(
            SHACL.path, SHACL.deactivated, SHACL.targetClass, SHACL.targetNode, SHACL.targetSubjectsOf, SHACL.targetObjectsOf,
        )).toSet()
    }

    /**
     * The resources of [index] that are shapes: instances of a shape class, or subjects of a shape parameter,
     * `sh:path`, `sh:deactivated` or a target. Only IRIs unless [blankNodes], as a computed `shnex:conformsToShape`
     * shape must be one (§4.5.3).
     */
    private fun shapeCandidates(index: ShapeGraphIndex, blankNodes: Boolean): Set<RdfResource> {
        val out = LinkedHashSet<RdfResource>()
        for (t in index.triples) {
            index.tick("shape discovery")
            val subject = t.subject
            if (subject !is Iri && !(blankNodes && subject is BlankNode)) continue
            if (t.predicate in shapeMarkers || (t.predicate == RDF.type && t.obj in shapeClasses)) out.add(subject)
        }
        return out
    }

    /** Static shape dependency graph → shapes that belong to a recursive strongly connected component. */
    private fun recursiveComponents(
        refNodes: Map<RdfResource, CompiledNodeShape>,
        refProps: Map<RdfResource, CompiledPropertyShape>,
        exprShapes: ExpressionShapes,
        functions: SparqlFunctionDeclarationSet,
        budget: ValidationBudget,
    ): Map<RdfResource, Int> {
        // A SPARQL query of a shape may call any SHACL SPARQL function, whose node expression body validates against
        // its shapes while the shape is checked: those shapes are dependencies of every shape that runs SPARQL
        // (sh:sparql, a SPARQL-based constraint component, a SPARQL node expression). A function body's own queries
        // may call the others, so the shapes of all bodies are taken, with the candidates of computed shapes.
        val sparqlBodyShapes: List<RdfResource> = functions.expressionBodies.flatMap { budget.tick("recursion analysis"); exprShapes.of(it) }.distinct()
        fun runsSparql(constraints: List<PropertyConstraint>, expressions: List<NodeExpression>) =
            sparqlBodyShapes.isNotEmpty() &&
                (constraints.any { it.unwrapped().let { c -> c is PropertyConstraint.Sparql || c is PropertyConstraint.SparqlComponent } } ||
                    expressions.any { NodeExpressions.runsSparql(it, exprShapes.index) })
        val successors = HashMap<RdfResource, List<RdfResource>>()
        for ((node, ps) in refProps) {
            // The node expressions of sh:values / sh:defaultValue / sh:expression / sh:nodeByExpression are evaluated
            // while checking the shape, so their shapes are dependencies; so are the candidates of a computed
            // sh:nodeByExpression, any of which it can produce.
            val expressions = shapeExpressions(ps) + ps.constraints.flatMap { constraintExpressions(it) }
            successors[node] = ps.constraints.flatMap { constraintRefs(it) + computedRefs(it, exprShapes) } +
                ps.logicalParts.flatMap { it.operandRefs() } + ps.nestedPropertyShapes.map { it.shapeNode } + exprShapes.of(ps) +
                (if (runsSparql(ps.constraints, expressions)) sparqlBodyShapes else emptyList())
        }
        // A shape node that is also a property shape is always checked as a property shape (see conformance checks).
        for ((node, cn) in refNodes) {
            if (node in refProps) continue
            // A non-constant sh:deactivated is evaluated before anything else of the shape: its shapes are
            // dependencies too, and so are those of the reifiers' sh:deactivated of its parameter triples.
            val expressions = cn.nodeConstraints.flatMap { constraintExpressions(it) } + shapeExpressions(cn)
            successors[node] = cn.nodeRefs + cn.nodeByExpressionRefs + cn.logicalParts.flatMap { it.operandRefs() } +
                cn.nodeConstraints.flatMap { constraintRefs(it) + computedRefs(it, exprShapes) } + cn.propertyShapes.map { it.shapeNode } +
                expressions.flatMap { exprShapes.of(it) } +
                (if (runsSparql(cn.nodeConstraints, expressions)) sparqlBodyShapes else emptyList())
        }
        val components = stronglyConnectedComponents(successors.keys.toList(), budget) { v -> successors[v].orEmpty().filter { it in successors } }
        val out = HashMap<RdfResource, Int>()
        var id = 0
        for (component in components) {
            if (component.size > 1 || successors[component[0]].orEmpty().contains(component[0])) {
                component.forEach { out[it] = id }
                id++
            }
        }
        return out
    }

    // --- unsupported features -------------------------------------------------------------------------------------

    private val shExpression = Iri(SHACL.namespace + "expression")
    private val shTarget = Iri(SHACL.namespace + "target")

    /** True for SHACL 1.2 SPARQL node expressions (`sh:SelectExpression`, `sh:SPARQLExprExpression`, `sh:select`, `sh:sparqlExpr`). */
    private fun isNodeExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean {
        val node = term as? RdfResource ?: return false
        val types = index.objects(node, RDF.type)
        return SHACL.SelectExpression in types || SHACL.SPARQLExprExpression in types ||
            listOf(SHACL.selectExpression, SHACL.exprExpression, SHACL.select, ShaclSparqlTerms.sparqlExpr).any { index.objects(node, it).isNotEmpty() }
    }

    /**
     * Blank node `sh:targetNode` values. In SHACL 1.2 the values of `sh:targetNode` are node expressions: an IRI or
     * a literal is a constant, a blank node is a computed expression. A blank node of the shapes graph can only
     * denote a node of the data graph when both graphs share it (the same graph validated against itself, or shapes
     * discovered from the data). The rule is therefore:
     *
     * 1. **Compile time (this function).** The blank node is a node expression, whatever the data graph is, when its
     *    own triples use vocabulary that has **no reading as data**: a SPARQL expression (`sh:select`,
     *    `sh:sparqlExpr`, the SHACL SPARQL expression types), the SHACL node-expression (`shnex:`) or SPARQL
     *    function (`sparql:`) namespaces as predicate or type, or a single call `[ ex:fn ( … ) ]` of a function
     *    **declared** in the shapes graph (`ex:fn` typed `sh:Function` / `sh:SPARQLFunction` or described with SHACL
     *    function vocabulary such as `sh:parameter`). It is evaluated when it compiles ([NodeExpressions.compile]),
     *    else reported per [ValidationConfig.unsupportedFeatures] (W3C `sparql/targets/targetNode-select-001`
     *    validates such a shapes graph against itself).
     * 2. **Validation time.** Any other blank node — a plain one, an RDF list cell, one that carries `sh:`
     *    predicates or a SHACL type (a shape used as data), a call-shaped one `[ ex:fn ( … ) ]` with `ex:fn`
     *    undeclared — is an **ordinary target when it is a node of the data graph**, regardless of its predicates.
     *    When it is not, it can never be a focus node and the shape would silently go unvalidated: it is then an
     *    unsupported node expression, handled per [ValidationConfig.unsupportedFeatures] like the ones of rule 1
     *    (failure, or a report warning under [UnsupportedFeatureHandling.IGNORE_WITH_WARNING]).
     */
    private fun isTargetNodeExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean {
        if (term !is BlankNode) return false
        val predicates = index.predicates(term)
        if (predicates.isEmpty()) return false
        if (isNodeExpression(term, index)) return true
        fun expressionVocabulary(t: RdfTerm) = t is Iri && (t.value.startsWith(ShaclSparqlTerms.SHNEX_NAMESPACE) || t.value.startsWith(ShaclSparqlTerms.SPARQL_NAMESPACE))
        if (predicates.any { expressionVocabulary(it) }) return true
        if (index.objects(term, RDF.type).any { expressionVocabulary(it) }) return true
        val call = callPredicate(term, index) ?: return false
        return isDeclaredFunction(call, index)
    }

    /** `fn` when [node] has the syntax of a function call `[ fn ( … ) ]`: a single triple whose object is an RDF list. */
    private fun callPredicate(node: BlankNode, index: ShapeGraphIndex): Iri? {
        val call = index.predicates(node).singleOrNull() ?: return null
        val argument = index.objects(node, call).singleOrNull() ?: return null
        return call.takeIf { argument == RDF.nil || (argument is BlankNode && index.objects(argument, RDF.first).isNotEmpty()) }
    }

    /** A function IRI declared in the shapes graph: typed with a SHACL class or described with SHACL predicates. */
    private fun isDeclaredFunction(function: RdfTerm, index: ShapeGraphIndex): Boolean {
        val node = function as? RdfResource ?: return false
        if (index.objects(node, RDF.type).any { it is Iri && it.value.startsWith(SHACL.namespace) }) return true
        return index.predicates(node).any { it.value.startsWith(SHACL.namespace) }
    }

    /**
     * True when [term] compiles as a node expression ([NodeExpressions.compile]): a constant, a SPARQL expression, or
     * any expression of the library. An ill-formed one counts as supported: the compiler reports it with its own error
     * where the expression is used (a deactivated shape never compiles its expressions). A blank node that is no node
     * expression at all (such as a call of an undeclared function) is not.
     */
    private fun isSupportedExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean =
        try {
            NodeExpressions.compile(term, index) != null
        } catch (_: ShapeCompileException) {
            true
        }

    private val shapeReferencePredicates: List<Iri> by lazy {
        listOf(
            SHACL.`property`, SHACL.node, SHACL.`not`, SHACL.qualifiedValueShape, SHACL.someValue, SHACL.memberShape,
            SHACL.shape, SHACL.reifierShape, SHACL.targetWhere,
        )
    }

    /** Shapes reachable from some roots ([shapeReachableNodes]), and which candidates their computed shape references need. */
    private class Reach(val shapes: Set<RdfResource>, val needsCandidates: Boolean)

    /**
     * [roots] and every node reachable from them through shape-valued parameters (a constant `sh:nodeByExpression`
     * IRI included) and through the shapes of the node expressions at `sh:values`, `sh:defaultValue`, `sh:targetNode`,
     * `sh:expression` and `sh:nodeByExpression` ([NodeExpressions.shapeRefs]). The candidates of computed shape
     * references are not followed: [Reach.needsCandidates] tells whether there are any.
     */
    private fun shapeReachableNodes(
        index: ShapeGraphIndex,
        roots: Collection<RdfResource>,
        exprShapes: ExpressionShapes,
        budget: ValidationBudget,
    ): Reach {
        val out = LinkedHashSet<RdfResource>()
        val pending = ArrayDeque<RdfResource>()
        var needsCandidates = false
        fun visit(term: RdfTerm) {
            if (term is RdfResource && out.add(term)) pending.add(term)
        }
        /** The shapes of the node expression [term]; constants and SPARQL expressions reference none. */
        fun expression(term: RdfTerm, computedShape: Boolean) {
            if (term !is BlankNode) return
            if (computedShape) needsCandidates = true
            if (isNodeExpression(term, index)) return
            // An ill-formed expression references none: the compiler reports it.
            val expr = try {
                NodeExpressions.compile(term, index)
            } catch (_: ShapeCompileException) {
                null
            } ?: return
            exprShapes.strict(expr).forEach(::visit)
            if (exprShapes.computes(expr)) needsCandidates = true
        }
        roots.forEach(::visit)
        while (pending.isNotEmpty()) {
            budget.tick("feature detection")
            val shape = pending.removeFirst()
            for (p in shapeReferencePredicates) index.objects(shape, p).forEach(::visit)
            for (p in listOf(SHACL.`and`, SHACL.`or`, SHACL.xone)) {
                // Malformed lists are reported by the compiler itself.
                index.objects(shape, p).forEach { head -> runCatching { index.parseRdfList(head) }.getOrDefault(emptyList()).forEach(::visit) }
            }
            for (p in expressionPositions) index.objects(shape, p).forEach { expression(it, computedShape = false) }
            // The sh:deactivated of a SPARQL-based constraint is a node expression too.
            for (c in index.objects(shape, SHACL.sparql)) {
                if (c is RdfResource) index.objects(c, SHACL.deactivated).forEach { expression(it, computedShape = false) }
            }
            // So is the sh:deactivated of a reifier of one of the shape's parameter triples (Core §3.1.6). On any other
            // triple it has no effect, so the shapes it reads are not validated against.
            for (p in index.predicates(shape)) {
                if (!isParameterPredicate(p, index)) continue
                for (o in index.objects(shape, p)) {
                    budget.tick("feature detection")
                    for (r in index.reifiersForClaim(RdfTriple(shape, p, o))) index.objects(r, SHACL.deactivated).forEach { expression(it, computedShape = false) }
                }
            }
            // An IRI is a constant shape reference; any other value computes its shapes.
            index.objects(shape, SHACL.nodeByExpression).forEach { if (it is Iri) visit(it) else expression(it, computedShape = true) }
        }
        return Reach(out, needsCandidates)
    }

    private val expressionPositions: List<Iri> by lazy {
        listOf(ShaclSparqlTerms.values, ShaclSparqlTerms.defaultValue, SHACL.targetNode, shExpression, SHACL.deactivated)
    }

    /**
     * Unsupported constructs: [strict] of the shapes the run validates against, by description, and [candidateOnly]:
     * those of shapes that only a computed shape reference can name, by shape (R8).
     */
    private class Detected(val strict: Map<String, UnsupportedShaclFeature>, val candidateOnly: Map<RdfResource, String>)

    /**
     * Recognised SHACL constructs the native engine cannot evaluate. They are reported instead of silently
     * compiling to something that always conforms (or targets every node): blank nodes at node expression positions
     * that are no node expression (at `sh:deactivated` of a shape or a reifier too), SPARQL expressions as `sh:targetWhere` values,
     * and `sh:target`. Node-expression positions are only inspected on shape-reachable nodes, so data triples that
     * share the shapes graph (`validate(g, g)`, discovered shapes graphs) are never mistaken for expressions. The
     * shapes only the candidates of computed shape references reach do not fail the run: their constructs are
     * [Detected.candidateOnly], which fails an expression that validates against such a shape.
     */
    private fun detectUnsupportedFeatures(
        index: ShapeGraphIndex,
        nodeShapes: List<RdfResource>,
        extraRoots: List<RdfResource>,
        functionBodies: List<NodeExpression>,
        exprShapes: ExpressionShapes,
        budget: ValidationBudget,
    ): Detected {
        val out = LinkedHashMap<String, UnsupportedShaclFeature>()
        val candidateOnly = LinkedHashMap<RdfResource, String>()
        val functions = LinkedHashSet<Iri>()
        // The shapes of the node expression bodies of the functions SPARQL queries may call are compiled, so they are
        // shapes the run validates against, reachable from nothing else maybe; so are the candidates of a body that
        // computes its shapes.
        val bodyShapes = functionBodies.flatMap { budget.tick("feature detection"); exprShapes.strict(it) }
        val bodiesNeedCandidates = functionBodies.any { exprShapes.computes(it) }
        val reach = shapeReachableNodes(
            index, nodeShapes + extraRoots + index.subjects(RDF.type, SHACL.PropertyShape) + bodyShapes, exprShapes, budget,
        )
        val shapes = reach.shapes
        val candidateShapes =
            if (reach.needsCandidates || bodiesNeedCandidates) shapeReachableNodes(index, exprShapes.allCandidates - shapes, exprShapes, budget).shapes - shapes else emptySet()
        fun expression(term: RdfTerm) =
            if (isNodeExpression(term, index)) UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION else UnsupportedShaclFeature.NODE_EXPRESSION
        for (t in index.triples) {
            budget.tick("feature detection")
            if (t.predicate == ShaclSparqlTerms.bodyExpression && t.subject is Iri) functions.add(t.subject as Iri)
            // A reifier deactivates the constraint of the parameter triple it reifies, whose subject is a shape (on
            // another triple it has no effect, so it is not inspected). So does a SPARQL-based constraint, the value of
            // sh:sparql of a shape.
            val owner = if (t.predicate == SHACL.deactivated) {
                val reified = index.objects(t.subject, RDF.reifies).filterIsInstance<TripleTerm>()
                    .filter { isParameterPredicate(it.triple.predicate, index) }.map { it.triple.subject } +
                    index.subjects(SHACL.sparql, t.subject)
                reified.firstOrNull { it in shapes } ?: reified.firstOrNull { it in candidateShapes } ?: t.subject
            } else {
                t.subject
            }
            if (owner !in shapes && owner !in candidateShapes) continue
            val (description, feature) = unsupportedFeature(t, index, ::expression) ?: continue
            if (owner in shapes) {
                out[description] = feature
            } else {
                candidateOnly[owner] = candidateOnly[owner]?.let { "$it; $description" } ?: description
            }
        }
        // Functions whose body is a SPARQL or other node expression are evaluated (SparqlFunctionDeclarations). A
        // declared function library is harmless; calling one whose body is no node expression at all from SPARQL is
        // not evaluable.
        functions.retainAll { budget.tick("feature detection"); !SparqlFunctionDeclarations.compiles(it, index) }
        for (function in functions) {
            if (SparqlFunctionDeclarations.calledFromSparql(function, index)) {
                out["SHACL 1.2 function $function (sh:bodyExpression) called from a SPARQL query"] = UnsupportedShaclFeature.SHACL_FUNCTION
            }
        }
        return Detected(out, candidateOnly)
    }

    /** The unsupported construct the shape triple [t] declares, as a description and its category, or null. */
    private fun unsupportedFeature(
        t: RdfTriple,
        index: ShapeGraphIndex,
        expression: (RdfTerm) -> UnsupportedShaclFeature,
    ): Pair<String, UnsupportedShaclFeature>? =
        when {
            t.predicate == ShaclSparqlTerms.values && !isSupportedExpression(t.obj, index) ->
                "sh:values node expression on ${t.subject}" to expression(t.obj)
            t.predicate == ShaclSparqlTerms.defaultValue && !isSupportedExpression(t.obj, index) ->
                "sh:defaultValue node expression on ${t.subject}" to expression(t.obj)
            t.predicate == shExpression && !isSupportedExpression(t.obj, index) ->
                "sh:expression constraint on ${t.subject}" to expression(t.obj)
            t.predicate == shTarget ->
                "sh:target (SPARQL-based or custom target) on ${t.subject}" to UnsupportedShaclFeature.CUSTOM_TARGET
            t.predicate == SHACL.targetWhere && isNodeExpression(t.obj, index) ->
                "sh:targetWhere with a SPARQL node expression on ${t.subject}" to UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION
            t.predicate == SHACL.targetNode && isTargetNodeExpression(t.obj, index) && !isSupportedExpression(t.obj, index) ->
                "sh:targetNode with a node expression on ${t.subject}" to expression(t.obj)
            t.predicate == SHACL.nodeByExpression && t.obj is BlankNode && !isSupportedExpression(t.obj, index) ->
                "sh:nodeByExpression with a computed node expression on ${t.subject}" to expression(t.obj)
            // SHACL 1.2 Core §3.1.6: the sh:deactivated of a shape, or of a reifier of a parameter triple (one
            // constraint), may be any node expression.
            t.predicate == SHACL.deactivated && t.obj is BlankNode && !isSupportedExpression(t.obj, index) ->
                "sh:deactivated node expression on ${t.subject}" to expression(t.obj)
            (t.predicate == SHACL.reifierShape || (t.predicate == SHACL.reificationRequired && isLexicallyTrue(t.obj))) &&
                hasNonPredicatePath(t.subject, index) -> {
                val parameter = if (t.predicate == SHACL.reifierShape) "sh:reifierShape" else "sh:reificationRequired"
                "$parameter on the non-predicate path of ${t.subject} (reifier constraints need a predicate IRI as sh:path)" to
                    UnsupportedShaclFeature.REIFIER_CONSTRAINT_ON_COMPLEX_PATH
            }
            else -> null
        }

    /**
     * True for an active property shape whose `sh:path` is not a predicate IRI. `sh:reifierShape` and
     * `sh:reificationRequired` constrain the reifiers of the triples a path traverses, which the engine only knows
     * for predicate paths (`focus path value`): on any other path they would silently constrain nothing.
     */
    private fun hasNonPredicatePath(shape: RdfResource, index: ShapeGraphIndex): Boolean {
        if (isDeactivated(shape, index)) return false
        val paths = index.objects(shape, SHACL.path)
        return paths.isNotEmpty() && paths.any { it !is Iri }
    }

    internal fun constraintRefs(c: PropertyConstraint): List<RdfResource> =
        when (c) {
            is PropertyConstraint.Deactivatable -> constraintRefs(c.constraint)
            is PropertyConstraint.Node -> listOf(c.nestedShape)
            is PropertyConstraint.NodeByExpression -> listOf(c.nestedShape)
            is PropertyConstraint.Qualified -> listOf(c.shape) + c.siblings
            is PropertyConstraint.MemberShape -> listOf(c.nestedShape)
            is PropertyConstraint.SomeValue -> listOf(c.nestedShape)
            is PropertyConstraint.Shape -> listOf(c.nestedShape)
            is PropertyConstraint.ReifierShape -> listOf(c.nestedShape)
            else -> emptyList()
        }

    /** The shapes a computed `sh:nodeByExpression` [c] can validate against: every candidate ([ExpressionShapes.allCandidates]). */
    private fun computedRefs(c: PropertyConstraint, exprShapes: ExpressionShapes): Collection<RdfResource> =
        if (c.unwrapped() is PropertyConstraint.NodeByExpressionComputed) exprShapes.allCandidates else emptyList()

    /**
     * The node expressions [c] evaluates: `sh:expression`, a computed `sh:nodeByExpression`, the non-constant
     * `sh:deactivated` of a SPARQL-based constraint, and the non-constant `sh:deactivated` of the reifiers of its
     * parameter triples ([PropertyConstraint.Deactivatable]).
     */
    internal fun constraintExpressions(c: PropertyConstraint): List<NodeExpression> =
        when (c) {
            is PropertyConstraint.Expression -> listOf(c.expr)
            is PropertyConstraint.NodeByExpressionComputed -> listOf(c.expr)
            is PropertyConstraint.Sparql -> listOfNotNull(c.deactivatedWhen)
            is PropertyConstraint.Deactivatable -> c.deactivatedWhen + constraintExpressions(c.constraint)
            else -> emptyList()
        }

    internal val resourceOrdering: Comparator<RdfResource> = Comparator { a, b ->
        when {
            a is Iri && b is Iri -> a.value.compareTo(b.value)
            a is BlankNode && b is BlankNode -> a.id.compareTo(b.id)
            a is Iri -> -1
            else -> 1
        }
    }

    private fun findNodeShapes(index: ShapeGraphIndex, budget: ValidationBudget): List<RdfResource> {
        val triples = index.triples
        val set = LinkedHashSet<RdfResource>()
        for (t in triples) {
            budget.tick("shape discovery")
            if (t.predicate == RDF.type &&
                (t.obj == SHACL.NodeShape || t.obj == SHACL.ShapeClass || t.obj == SHACL.Shape)
            ) {
                set.add(t.subject)
            }
        }
        // Implicit node shapes: subjects with targets (explicit, or an implicit class target because the subject is
        // a SHACL instance of rdfs:Class) and shape parameters but no `rdf:type` (W3C misc/severity-002, etc.).
        for (t in triples) {
            budget.tick("shape discovery")
            val s = t.subject
            if (s in set) continue
            if (index.objects(s, SHACL.path).isNotEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            if (looksLikeImplicitNodeShape(s, index)) set.add(s)
        }
        // Standalone shapes declared with `sh:path` plus targets (W3C path-* manifests); covers PropertyShape
        // roots whether or not an explicit `rdf:type` triple is present.
        for (t in triples) {
            budget.tick("shape discovery")
            val s = t.subject
            if (s in set) continue
            if (index.objects(s, SHACL.path).isEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            set.add(s)
        }
        budget.check("shape discovery")
        return set.toList()
    }

    private fun hasShapeTargets(s: RdfResource, index: ShapeGraphIndex): Boolean =
        index.objects(s, SHACL.targetClass).isNotEmpty() ||
            index.objects(s, SHACL.targetNode).isNotEmpty() ||
            index.objects(s, SHACL.targetSubjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetObjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetWhere).isNotEmpty() ||
            (s is Iri && index.isInstanceOf(s, RDFS.Class))

    private val shapeParameters: List<Iri> by lazy {
        listOf(
            SHACL.`property`, SHACL.node, SHACL.`and`, SHACL.`or`, SHACL.xone, SHACL.`not`, SHACL.closed,
            SHACL.ignoredProperties, SHACL.datatype, SHACL.nodeByExpression, SHACL.nodeKind, SHACL.`class`,
            SHACL.pattern, SHACL.minCount, SHACL.maxCount, SHACL.uniqueValuesFor, SHACL.severity, SHACL.targetWhere,
            SHACL.`in`, SHACL.hasValue, SHACL.minInclusive, SHACL.maxInclusive, SHACL.minExclusive, SHACL.maxExclusive,
            SHACL.minLength, SHACL.maxLength, SHACL.languageIn, SHACL.sparql, SHACL.equals, SHACL.disjoint,
            SHACL.shape, SHACL.rootClass, SHACL.singleLine, SHACL.minListLength, SHACL.maxListLength,
            SHACL.memberShape, SHACL.uniqueMembers, SHACL.someValue, shExpression,
        )
    }

    private fun looksLikeImplicitNodeShape(s: RdfResource, index: ShapeGraphIndex): Boolean =
        shapeParameters.any { index.objects(s, it).isNotEmpty() }

    private fun compileNodeShape(subject: RdfResource, index: ShapeGraphIndex, config: ValidationConfig): CompiledNodeShape {
        if (isDeactivated(subject, index)) {
            return CompiledNodeShape(
                shapeNode = subject, targets = Targets(), propertyShapes = emptyList(),
                severity = ViolationSeverity.VIOLATION, messages = emptyList(), closed = ClosedMode.NONE,
                ignoredProperties = emptySet(), logicalParts = emptyList(), nodeRefs = emptyList(), deactivated = true,
            )
        }
        val explicitTargetClasses = index.objects(subject, SHACL.targetClass).filterIsInstance<Iri>()
        // SHACL §2.1.3.3: a shape that is a SHACL instance of rdfs:Class in the shapes graph targets its instances.
        val implicitClassTargets =
            if (subject is Iri && (index.isInstanceOf(subject, RDFS.Class) || index.objects(subject, RDF.type).contains(SHACL.ShapeClass))) {
                listOf(subject)
            } else {
                emptyList()
            }
        val targetNodeValues = index.objects(subject, SHACL.targetNode)
        // An expression that does not compile (no node expression at all) is reported as unsupported.
        val targetNodeExpressions = targetNodeValues
            .filter { isTargetNodeExpression(it, index) }
            .mapNotNull { NodeExpressions.compile(it, index) }
        val targetNodes = targetNodeValues
            .filterNot { isTargetNodeExpression(it, index) }
            .onEach { ensureShapeTermAllowed(it, config) }
        val callShapedTargets = LinkedHashMap<BlankNode, Iri>()
        for (node in targetNodes) if (node is BlankNode) callPredicate(node, index)?.let { callShapedTargets[node] = it }
        val targets = Targets(
            targetClasses = (explicitTargetClasses + implicitClassTargets).distinct(),
            targetNodes = targetNodes,
            targetSubjectsOf = index.objects(subject, SHACL.targetSubjectsOf).filterIsInstance<Iri>(),
            targetObjectsOf = index.objects(subject, SHACL.targetObjectsOf).filterIsInstance<Iri>(),
            callShapedTargets = callShapedTargets,
            targetNodeExpressions = targetNodeExpressions,
        )

        val nodeSev =
            parseSeverityValues(index.objects(subject, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)
        val targetWhereRefs =
            index.objects(subject, SHACL.targetWhere).filterIsInstance<RdfResource>().filterNot { isNodeExpression(it, index) }

        if (index.objects(subject, SHACL.path).isNotEmpty()) {
            // A shape with sh:path is a property shape even when it declares targets: its sh:property values apply to
            // each value node (nested property shapes) and node-shape-only parameters such as sh:closed are ignored.
            return CompiledNodeShape(
                shapeNode = subject,
                targets = targets,
                targetWhereRefs = targetWhereRefs,
                propertyShapes = listOf(compilePropertyShape(subject, index, config)),
                severity = nodeSev.level,
                severityCustomIri = nodeSev.customIri,
                messages = index.objects(subject, SHACL.message).filterIsInstance<Literal>(),
                closed = ClosedMode.NONE,
                ignoredProperties = emptySet(),
                logicalParts = emptyList(),
                nodeRefs = emptyList(),
            )
        }

        // SHACL 1.2 Core 3.3: sh:values / sh:defaultValue belong to property shapes with a predicate path.
        for (predicate in listOf(ShaclSparqlTerms.values, ShaclSparqlTerms.defaultValue)) {
            if (index.objects(subject, predicate).isNotEmpty()) {
                val name = predicate.value.substringAfter(SHACL.namespace)
                throw ShapeCompileException("Shape $subject has sh:$name, which needs a property shape with a predicate path as sh:path")
            }
        }

        val links = LinkedHashMap<Pair<Iri, RdfResource>, List<NodeExpression>>()
        // Property shapes have their own severity (default sh:Violation); they never inherit the node shape's.
        val linkedPropShapes =
            index.objects(subject, SHACL.`property`)
                .mapNotNull { ps ->
                    val node = ps as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (!activeLink(subject, SHACL.`property`, node, index, links)) return@mapNotNull null
                    compilePropertyShape(node, index, config)
                }

        val closedAllowedPredicates =
            index.objects(subject, SHACL.`property`).filterIsInstance<RdfResource>()
                .flatMap { index.objects(it, SHACL.path) }.filterIsInstance<Iri>().toSet()

        val closedTerms = index.objects(subject, SHACL.closed)
        val ignoredTerms = index.objects(subject, SHACL.ignoredProperties)
        // sh:ignoredProperties is an optional parameter of sh:ClosedConstraintComponent: a reifier of either triple
        // deactivates the constraint.
        val closedConditions = reifierDeactivation(
            subject, closedTerms.map { SHACL.closed to it } + ignoredTerms.map { SHACL.ignoredProperties to it }, index,
        )
        val closedMode =
            when {
                closedConditions == null -> ClosedMode.NONE
                closedTerms.contains(SHACL.ByTypes) -> ClosedMode.BY_TYPES
                closedTerms.any { isLexicallyTrue(it) } -> ClosedMode.TRUE
                else -> ClosedMode.NONE
            }
        val ignored = ignoredTerms.flatMap { term ->
            when (term) {
                is Iri -> listOf(term)
                is BlankNode -> index.parseRdfList(term).filterIsInstance<Iri>()
                else -> emptyList()
            }
        }.toSet()

        val logicalParts = compileLogicalParts(subject, index)
        val nodeRefsOnShape = index.objects(subject, SHACL.node).filterIsInstance<RdfResource>()
            .filter { activeLink(subject, SHACL.node, it, index, links) }
        // IRIs are constant shape references; other values are computed (PropertyConstraint.NodeByExpressionComputed).
        val nodeByExpressionRefs = index.objects(subject, SHACL.nodeByExpression).filterIsInstance<Iri>()
            .filter { activeLink(subject, SHACL.nodeByExpression, it, index, links) }
        val nodeConstraints = compileConstraints(subject, index, config, ConstraintFlavor.NODE_SCALAR, null)
        val uniqueValuesForConditions =
            reifierDeactivation(subject, index.objects(subject, SHACL.uniqueValuesFor).map { SHACL.uniqueValuesFor to it }, index)
        val uniqueValuesForProps = parseUniqueValuesFor(subject, index).takeIf { uniqueValuesForConditions != null }.orEmpty()

        return CompiledNodeShape(
            deactivatedWhen = deactivationExpression(subject, index),
            shapeNode = subject,
            targets = targets,
            targetWhereRefs = targetWhereRefs,
            propertyShapes = linkedPropShapes,
            severity = nodeSev.level,
            severityCustomIri = nodeSev.customIri,
            messages = index.objects(subject, SHACL.message).filterIsInstance<Literal>(),
            closed = closedMode,
            ignoredProperties = ignored,
            logicalParts = logicalParts,
            nodeRefs = nodeRefsOnShape,
            nodeByExpressionRefs = nodeByExpressionRefs,
            nodeConstraints = nodeConstraints,
            uniqueValuesForProps = uniqueValuesForProps,
            closedAllowedPredicates = closedAllowedPredicates,
            reifierConditions = ReifierConditions(
                links = links,
                closed = closedConditions.orEmpty().takeIf { closedMode != ClosedMode.NONE }.orEmpty(),
                uniqueValuesFor = uniqueValuesForConditions.orEmpty().takeIf { uniqueValuesForProps.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    /**
     * Whether the `sh:property`, `sh:node` or `sh:nodeByExpression` triple ([subject] [predicate] [target]) declares a
     * constraint, that is, is not deactivated by the constant `true` at one of its reifiers (SHACL 1.2 Core §3.1.6). A
     * non-constant deactivation is recorded in [links].
     */
    private fun activeLink(
        subject: RdfResource,
        predicate: Iri,
        target: RdfResource,
        index: ShapeGraphIndex,
        links: MutableMap<Pair<Iri, RdfResource>, List<NodeExpression>>,
    ): Boolean {
        val conditions = reifierDeactivation(subject, listOf(predicate to target), index) ?: return false
        if (conditions.isNotEmpty()) links[predicate to target] = conditions
        return true
    }

    /**
     * The `sh:and`, `sh:or`, `sh:xone` and `sh:not` constraints of [subject], without those a reifier of their triple
     * deactivates with the constant `true`, and with the non-constant deactivations (SHACL 1.2 Core §3.1.6).
     */
    private fun compileLogicalParts(subject: RdfResource, index: ShapeGraphIndex): MutableList<NodeLogicalPart> {
        val logicalParts = mutableListOf<NodeLogicalPart>()
        fun each(predicate: Iri, build: (RdfTerm, List<NodeExpression>) -> NodeLogicalPart) {
            for (value in index.objects(subject, predicate)) {
                val part = build(value, emptyList())
                val conditions = reifierDeactivation(subject, listOf(predicate to value), index) ?: continue
                logicalParts.add(if (conditions.isEmpty()) part else build(value, conditions))
            }
        }
        each(SHACL.`and`) { head, d -> NodeLogicalPart.And(parseShapeRefList(head, index), d) }
        each(SHACL.`or`) { head, d -> NodeLogicalPart.Or(parseShapeRefList(head, index), d) }
        each(SHACL.xone) { head, d -> NodeLogicalPart.Xone(parseShapeRefList(head, index), d) }
        each(SHACL.`not`) { n, d ->
            val ref = n as? RdfResource ?: throw ShapeCompileException("sh:not expects a shape reference, got $n")
            NodeLogicalPart.Not(ref, d)
        }
        return logicalParts
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
    private fun compileConstraints(
        subject: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
        flavor: ConstraintFlavor,
        path: ShaclPath?,
    ): MutableList<PropertyConstraint> {
        val constraints = mutableListOf<PropertyConstraint>()
        fun atMostOne(pred: Iri): RdfTerm? {
            val values = index.objects(subject, pred)
            if (values.size > 1) {
                throw ShapeCompileException("Shape $subject has ${values.size} values for $pred; SHACL allows at most one")
            }
            return values.firstOrNull()
        }
        // SHACL 1.2 Core §3.1.6: a reifier of one of the parameter triples a constraint uses deactivates it, for every
        // focus node (the constant true: the constraint is left out) or per focus node (Deactivatable).
        fun add(c: PropertyConstraint, vararg triples: Pair<Iri, RdfTerm?>) {
            val conditions = reifierDeactivation(subject, triples.mapNotNull { (p, o) -> o?.let { p to it } }, index) ?: return
            constraints.add(if (conditions.isEmpty()) c else PropertyConstraint.Deactivatable(c, conditions))
        }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            atMostOne(SHACL.minCount)?.let { add(PropertyConstraint.MinCount(parseNonNegativeInt(it, "sh:minCount")), SHACL.minCount to it) }
            atMostOne(SHACL.maxCount)?.let { add(PropertyConstraint.MaxCount(parseNonNegativeInt(it, "sh:maxCount")), SHACL.maxCount to it) }
        }
        for (dtObj in index.objects(subject, SHACL.datatype)) {
            val override = annotatedConstraintSeverity(subject, SHACL.datatype, dtObj, index)
            val allowed =
                when (dtObj) {
                    is Iri -> listOf(dtObj)
                    is BlankNode ->
                        index.parseRdfList(dtObj).map {
                            it as? Iri ?: throw ShapeCompileException("sh:datatype list entries must be IRIs: $it")
                        }
                    else -> throw ShapeCompileException("sh:datatype expects an IRI or RDF list, got $dtObj")
                }
            if (allowed.isNotEmpty()) {
                add(PropertyConstraint.Datatype(allowed, override, annotatedConstraintMessages(subject, SHACL.datatype, dtObj, index)), SHACL.datatype to dtObj)
            }
        }
        for (co in index.objects(subject, SHACL.`class`)) {
            when (co) {
                is Iri -> add(PropertyConstraint.Class(co), SHACL.`class` to co)
                is BlankNode -> {
                    val opts =
                        index.parseRdfList(co).map {
                            it as? Iri ?: throw ShapeCompileException("sh:class list entries must be IRIs: $it")
                        }
                    when (opts.size) {
                        0 -> Unit
                        1 -> add(PropertyConstraint.Class(opts.first()), SHACL.`class` to co)
                        else -> add(PropertyConstraint.ClassAnyOf(opts), SHACL.`class` to co)
                    }
                }
                // A literal (or triple term) is not a class: the shapes graph is ill-formed. Dropping the constraint
                // would validate nothing where the author asked for a class.
                else -> throw ShapeCompileException("sh:class of shape $subject expects a class IRI or an RDF list of IRIs, got $co")
            }
        }
        // Every sh:nodeKind value is its own constraint (all must hold); an RDF list value allows any listed kind.
        for (nk in index.objects(subject, SHACL.nodeKind)) {
            val kinds =
                when (nk) {
                    is Iri -> listOf(nk)
                    is BlankNode ->
                        index.parseRdfList(nk).map {
                            it as? Iri ?: throw ShapeCompileException("sh:nodeKind list entries must be IRIs: $it")
                        }
                    else -> throw ShapeCompileException(
                        "sh:nodeKind of shape $subject expects one of the sh:NodeKind IRIs (sh:IRI, sh:BlankNode, " +
                            "sh:Literal, ...) or an RDF list of them, got $nk",
                    )
                }
            if (kinds.isNotEmpty()) add(PropertyConstraint.NodeKind(kinds.distinct()), SHACL.nodeKind to nk)
        }
        val flagsTerm = atMostOne(SHACL.flags)
        val flags = flagsTerm?.let { literalString(it) ?: throw ShapeCompileException("sh:flags must be a string literal") }
        for (p in index.objects(subject, SHACL.pattern)) {
            val pat = literalString(p) ?: throw ShapeCompileException("sh:pattern must be a string literal")
            // sh:flags is a parameter of each pattern constraint, so its reifier deactivates all of them.
            add(PropertyConstraint.Pattern(pat, flags, compileShaclPattern(pat, flags)), SHACL.pattern to p, SHACL.flags to flagsTerm)
        }
        atMostOne(SHACL.minLength)?.let { add(PropertyConstraint.MinLength(parseNonNegativeInt(it, "sh:minLength")), SHACL.minLength to it) }
        atMostOne(SHACL.maxLength)?.let { add(PropertyConstraint.MaxLength(parseNonNegativeInt(it, "sh:maxLength")), SHACL.maxLength to it) }
        atMostOne(SHACL.`in`)?.let { head ->
            val terms = index.parseRdfList(head)
            terms.forEach { ensureShapeTermAllowed(it, config) }
            add(PropertyConstraint.In(terms), SHACL.`in` to head)
        }
        for (hv in index.objects(subject, SHACL.hasValue)) {
            ensureShapeTermAllowed(hv, config)
            add(PropertyConstraint.HasValue(hv), SHACL.hasValue to hv)
        }
        atMostOne(SHACL.languageIn)?.let { head ->
            val langs = index.parseRdfList(head).map {
                literalString(it) ?: throw ShapeCompileException("sh:languageIn list must be string literals")
            }
            add(PropertyConstraint.LanguageIn(langs), SHACL.languageIn to head)
        }
        atMostOne(SHACL.uniqueLang)?.let { add(PropertyConstraint.UniqueLang(isLexicallyTrue(it)), SHACL.uniqueLang to it) }
        atMostOne(SHACL.minListLength)?.let { add(PropertyConstraint.MinListLength(parseNonNegativeInt(it, "sh:minListLength")), SHACL.minListLength to it) }
        atMostOne(SHACL.maxListLength)?.let { add(PropertyConstraint.MaxListLength(parseNonNegativeInt(it, "sh:maxListLength")), SHACL.maxListLength to it) }
        index.objects(subject, SHACL.memberShape).filterIsInstance<RdfResource>().forEach {
            add(PropertyConstraint.MemberShape(it), SHACL.memberShape to it)
        }
        atMostOne(SHACL.uniqueMembers)?.let { add(PropertyConstraint.UniqueMembers(isLexicallyTrue(it)), SHACL.uniqueMembers to it) }
        atMostOne(SHACL.singleLine)?.let { add(PropertyConstraint.SingleLine(isLexicallyTrue(it)), SHACL.singleLine to it) }
        for (rc in index.objects(subject, SHACL.rootClass)) {
            val roots =
                when (rc) {
                    is Iri -> listOf(rc)
                    is BlankNode ->
                        index.parseRdfList(rc).map {
                            it as? Iri ?: throw ShapeCompileException("sh:rootClass list members must be IRIs")
                        }
                    else -> throw ShapeCompileException("sh:rootClass expects an IRI or RDF list")
                }
            add(PropertyConstraint.RootClass(roots), SHACL.rootClass to rc)
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
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
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
        // SHACL 1.2 Node Expressions §7: any shape. A value that is no node expression at all is reported as an
        // unsupported feature (see detectUnsupportedFeatures) and has no constraint.
        for (term in index.objects(subject, shExpression)) {
            NodeExpressions.compile(term, index)?.let { add(PropertyConstraint.Expression(term, it), shExpression to term) }
        }
        for (term in index.objects(subject, SHACL.nodeByExpression)) {
            if (term is Iri) continue // A constant shape reference: PropertyConstraint.NodeByExpression / nodeByExpressionRefs.
            NodeExpressions.compile(term, index)?.let { add(PropertyConstraint.NodeByExpressionComputed(term, it), SHACL.nodeByExpression to term) }
        }
        index.objects(subject, SHACL.equals).forEach {
            add(PropertyConstraint.EqualsPath(ShaclPathParser.parse(it, index)), SHACL.equals to it)
        }
        index.objects(subject, SHACL.disjoint).forEach {
            add(PropertyConstraint.DisjointPath(ShaclPathParser.parse(it, index)), SHACL.disjoint to it)
        }
        index.objects(subject, SHACL.minInclusive).forEach { add(PropertyConstraint.MinInclusive(it), SHACL.minInclusive to it) }
        index.objects(subject, SHACL.maxInclusive).forEach { add(PropertyConstraint.MaxInclusive(it), SHACL.maxInclusive to it) }
        index.objects(subject, SHACL.minExclusive).forEach { add(PropertyConstraint.MinExclusive(it), SHACL.minExclusive to it) }
        index.objects(subject, SHACL.maxExclusive).forEach { add(PropertyConstraint.MaxExclusive(it), SHACL.maxExclusive to it) }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            val qualifiedShapes = index.objects(subject, SHACL.qualifiedValueShape).filterIsInstance<RdfResource>()
            val minTerm = atMostOne(SHACL.qualifiedMinCount)
            val maxTerm = atMostOne(SHACL.qualifiedMaxCount)
            val disjointTerm = atMostOne(SHACL.qualifiedValueShapesDisjoint)
            val min = minTerm?.let { parseNonNegativeInt(it, "sh:qualifiedMinCount") }
            val max = maxTerm?.let { parseNonNegativeInt(it, "sh:qualifiedMaxCount") }
            val disjoint = disjointTerm?.let { isLexicallyTrue(it) } ?: false
            for (qvs in qualifiedShapes) {
                val siblings =
                    if (!disjoint) {
                        emptyList()
                    } else {
                        index.subjects(SHACL.`property`, subject)
                            .flatMap { parent -> index.objects(parent, SHACL.`property`) }
                            .filterIsInstance<RdfResource>()
                            .flatMap { sibling -> index.objects(sibling, SHACL.qualifiedValueShape) }
                            .filterIsInstance<RdfResource>()
                            .distinct()
                            .filter { it != qvs }
                    }
                // sh:QualifiedMinCountConstraintComponent and sh:QualifiedMaxCountConstraintComponent share
                // sh:qualifiedValueShape and sh:qualifiedValueShapesDisjoint: a reifier of either deactivates both.
                // The two stay one constraint unless the reifiers of sh:qualifiedMinCount and sh:qualifiedMaxCount
                // deactivate them differently.
                val shared = arrayOf(SHACL.qualifiedValueShape to qvs, SHACL.qualifiedValueShapesDisjoint to disjointTerm)
                val minOnly = minTerm?.let { reifierDeactivation(subject, listOf(SHACL.qualifiedMinCount to it), index) }
                val maxOnly = maxTerm?.let { reifierDeactivation(subject, listOf(SHACL.qualifiedMaxCount to it), index) }
                if (min == null || max == null || minOnly == maxOnly) {
                    add(PropertyConstraint.Qualified(qvs, min, max, disjoint, siblings), *shared, SHACL.qualifiedMinCount to minTerm, SHACL.qualifiedMaxCount to maxTerm)
                } else {
                    add(PropertyConstraint.Qualified(qvs, min, null, disjoint, siblings), *shared, SHACL.qualifiedMinCount to minTerm)
                    add(PropertyConstraint.Qualified(qvs, null, max, disjoint, siblings), *shared, SHACL.qualifiedMaxCount to maxTerm)
                }
            }
            // sh:reificationRequired is an optional parameter of sh:ReifierShapeConstraintComponent (Core §7.8.5): it is
            // part of the constraint of each sh:reifierShape value, and a constraint of its own only without one.
            val reifierShapes = index.objects(subject, SHACL.reifierShape).filterIsInstance<RdfResource>()
            val requiredTerm = atMostOne(SHACL.reificationRequired)
            val required = requiredTerm?.let { isLexicallyTrue(it) } ?: false
            if (reifierShapes.isEmpty()) {
                requiredTerm?.let { add(PropertyConstraint.ReificationRequired(required), SHACL.reificationRequired to it) }
            }
            reifierShapes.forEach {
                add(PropertyConstraint.ReifierShape(it, required), SHACL.reifierShape to it, SHACL.reificationRequired to requiredTerm)
            }
        }
        index.objects(subject, SHACL.sparql).filterIsInstance<RdfResource>().forEach { node ->
            compileSparqlConstraint(node, index, path)?.let { c -> add(c, SHACL.sparql to node) }
        }
        for (c in index.sparqlComponents.constraintsFor(subject, flavor == ConstraintFlavor.PROPERTY_SHAPE, path)) {
            add(c, *c.parameterTriples.toTypedArray())
        }
        return constraints
    }

    private fun parseUniqueValuesFor(subject: RdfResource, index: ShapeGraphIndex): List<Iri> {
        val values = index.objects(subject, SHACL.uniqueValuesFor)
        if (values.size > 1) throw ShapeCompileException("Shape $subject has several sh:uniqueValuesFor values; at most one is allowed")
        val raw = values.firstOrNull() ?: return emptyList()
        return when (raw) {
            is Iri -> listOf(raw)
            is BlankNode ->
                index.parseRdfList(raw).map {
                    it as? Iri ?: throw ShapeCompileException("sh:uniqueValuesFor list members must be IRIs")
                }
            else -> throw ShapeCompileException("sh:uniqueValuesFor expects an IRI or RDF list")
        }
    }

    private fun compilePropertyShape(
        ps: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
        ancestors: Set<RdfResource> = emptySet(),
    ): CompiledPropertyShape {
        if (ps in ancestors || ancestors.size >= 128) throw ShapeCompileException("Cyclic or excessively nested property shapes")
        val pathValues = index.objects(ps, SHACL.path)
        if (isDeactivated(ps, index)) {
            val pathNode = pathValues.firstOrNull() ?: RDF.nil
            val path = runCatching { ShaclPathParser.parse(pathNode, index) }.getOrDefault(ShaclPath.Predicate(RDF.nil))
            return CompiledPropertyShape(
                shapeNode = ps, path = path, pathNode = pathNode, pathTriples = emptyList(),
                severity = ViolationSeverity.VIOLATION, messages = emptyList(), constraints = emptyList(), deactivated = true,
            )
        }
        val pathTerm = pathValues.singleOrNull()
            ?: throw ShapeCompileException("Property shape $ps must have exactly one sh:path")
        val path = ShaclPathParser.parse(pathTerm, index)

        val constraints = compileConstraints(ps, index, config, ConstraintFlavor.PROPERTY_SHAPE, path)
        val logicalParts = compileLogicalParts(ps, index)

        val links = LinkedHashMap<Pair<Iri, RdfResource>, List<NodeExpression>>()
        val nestedPropertyShapes =
            index.objects(ps, SHACL.`property`)
                .mapNotNull { child ->
                    val node = child as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (!activeLink(ps, SHACL.`property`, node, index, links)) return@mapNotNull null
                    compilePropertyShape(node, index, config, ancestors + ps)
                }

        val psSev = parseSeverityValues(index.objects(ps, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)

        return CompiledPropertyShape(
            shapeNode = ps,
            path = path,
            pathNode = pathTerm,
            pathTriples = index.blankNodeClosure(pathTerm),
            severity = psSev.level,
            severityCustomIri = psSev.customIri,
            messages = index.objects(ps, SHACL.message).filterIsInstance<Literal>(),
            constraints = constraints,
            logicalParts = logicalParts,
            nestedPropertyShapes = nestedPropertyShapes,
            values = NodeExpressions.compileParameter(ps, ShaclSparqlTerms.values, path, index),
            defaultValue = NodeExpressions.compileParameter(ps, ShaclSparqlTerms.defaultValue, path, index),
            deactivatedWhen = deactivationExpression(ps, index),
            reifierConditions = ReifierConditions(links = links),
        )
    }

    // --- SHACL-SPARQL ---------------------------------------------------------------------------------------------

    private fun compileSparqlConstraint(node: RdfResource, index: ShapeGraphIndex, path: ShaclPath?): PropertyConstraint.Sparql? {
        if (isDeactivated(node, index)) return null
        val select = index.objects(node, SHACL.select).singleOrNull() as? Literal
        if (select == null || select is LangString || select.datatype != XSD.string) {
            throw ShapeCompileException("SPARQL constraint $node must have exactly one xsd:string sh:select")
        }
        val text = select.lexical
        // $PATH is replaced only as a variable token, never inside strings, IRIs or comments.
        // On a node shape $PATH stays unbound.
        val body = SparqlQueryTemplate.substitutePath(text, path?.let { { renderSparqlPath(it) } })
        val template = SparqlQueryTemplate.compileRestricted(
            SparqlPrefixes.prologue(SparqlPrefixes.collect(node, index)), body,
            SparqlQueryTemplate.SPARQL_CONSTRAINT_PRE_BOUND, "SPARQL constraint $node",
        )
        val severityIris = index.objects(node, SHACL.severity).filterIsInstance<Iri>()
        val sev = if (severityIris.isEmpty()) null else parseSeverityValues(severityIris, ViolationSeverity.VIOLATION)
        return PropertyConstraint.Sparql(
            constraintNode = node,
            template = template,
            messages = index.objects(node, SHACL.message).filterIsInstance<Literal>(),
            severity = sev?.level,
            severityCustomIri = sev?.customIri,
            annotations = compileResultAnnotations(node, index),
            deactivatedWhen = deactivationExpression(node, index),
        )
    }

    /** Renders a SHACL property path as a SPARQL 1.1 property path (for `$PATH` substitution). */
    internal fun renderSparqlPath(path: ShaclPath): String =
        when (path) {
            is ShaclPath.Predicate ->
                SparqlQueryTemplate.renderIri(path.iri.value)
                    ?: throw ShapeCompileException("IRI <${path.iri.value}> cannot be written as a SPARQL IRI reference in \$PATH")
            is ShaclPath.Inverse -> "^(${renderSparqlPath(path.child)})"
            is ShaclPath.Sequence -> path.segments.joinToString("/", "(", ")") { renderSparqlPath(it) }
            is ShaclPath.Alternative -> path.options.joinToString("|", "(", ")") { renderSparqlPath(it) }
            is ShaclPath.ZeroOrMore -> "(${renderSparqlPath(path.child)})*"
            is ShaclPath.OneOrMore -> "(${renderSparqlPath(path.child)})+"
            is ShaclPath.ZeroOrOne -> "(${renderSparqlPath(path.child)})?"
        }

    // --- helpers --------------------------------------------------------------------------------------------------

    /**
     * How the reifiers of the parameter triples [triples] (predicate, object) of [subject] deactivate the constraint
     * that uses them (SHACL 1.2 Core §3.1.6): null when the `sh:deactivated` of one of them is the constant `true` (the
     * constraint is deactivated for every focus node), else the node expressions of the non-constant ones, which
     * deactivate it for the focus nodes one of them produces exactly `true` for (none: the constraint is active). A
     * triple has at most one reifier with a value for `sh:deactivated`, and that reifier at most one value: more is a
     * compile error. A blank node that is no node expression is reported by feature detection and deactivates nothing.
     */
    private fun reifierDeactivation(subject: RdfResource, triples: List<Pair<Iri, RdfTerm>>, index: ShapeGraphIndex): List<NodeExpression>? {
        // Every triple is checked before a constant true leaves the constraint out.
        val deactivating = triples.mapNotNull { (predicate, obj) ->
            val reifiers = index.reifiersForClaim(RdfTriple(subject, predicate, obj)).filter { index.objects(it, SHACL.deactivated).isNotEmpty() }
            if (reifiers.size > 1) {
                throw ShapeCompileException(
                    "The triple $subject $predicate $obj has ${reifiers.size} reifiers with sh:deactivated; it can have at most one reifier with a value for sh:deactivated",
                )
            }
            reifiers.singleOrNull()?.also { deactivatedValues(it, index) }
        }
        val out = ArrayList<NodeExpression>()
        for (reifier in deactivating) {
            if (isDeactivated(reifier, index)) return null
            deactivationExpression(reifier, index)?.let { out.add(it) }
        }
        return out
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

    private fun parseShapeRefList(head: RdfTerm, index: ShapeGraphIndex): List<RdfResource> =
        index.parseRdfList(head).map {
            it as? RdfResource ?: throw ShapeCompileException("Shape list entries must be IRIs or blank nodes: $it")
        }

    private fun ensureShapeTermAllowed(term: RdfTerm, config: ValidationConfig) {
        if (term is TripleTerm && !config.allowTripleTermsInShapeParameters) {
            throw ShapeCompileException(
                "Triple-term value in shape parameter requires ValidationConfig.allowTripleTermsInShapeParameters=true (architecture §5.1 P1b)",
            )
        }
    }

    /**
     * The node expression of a non-constant `sh:deactivated` of [subject], a shape, a SPARQL-based constraint or a
     * reifier (SHACL 1.2 Core §3.1.6 and SHACL 1.2 SPARQL: at most one value), or null for none or a constant (the constant
     * `true` makes it [isDeactivated]; any other constant never produces `true`). A blank node that is no node
     * expression is reported by feature detection and gives null; an ill-formed one is a compile error, and so is a
     * second value ([deactivatedValues]).
     */
    private fun deactivationExpression(subject: RdfResource, index: ShapeGraphIndex): NodeExpression? {
        val expression = deactivatedValues(subject, index).firstOrNull { it is BlankNode } ?: return null
        return NodeExpressions.compile(expression, index)
    }

    /** True for a shape, SPARQL-based constraint or reifier whose `sh:deactivated` is the constant `true`. */
    private fun isDeactivated(subject: RdfResource, index: ShapeGraphIndex): Boolean =
        deactivatedValues(subject, index).any { isLexicallyTrue(it) }

    /**
     * The `sh:deactivated` values of [subject]. More than one is a compile error, checked before any of them is
     * read, so that `sh:deactivated true , [ … ]` is not taken for a deactivated shape.
     */
    private fun deactivatedValues(subject: RdfResource, index: ShapeGraphIndex): List<RdfTerm> {
        val values = index.objects(subject, SHACL.deactivated)
        if (values.size > 1) {
            throw ShapeCompileException("$subject has ${values.size} values for sh:deactivated; it can have at most one value")
        }
        return values
    }

    private data class SeverityParse(val level: ViolationSeverity, val customIri: Iri?)

    private fun parseSeverityValues(iris: List<Iri>, default: ViolationSeverity): SeverityParse {
        val i = iris.firstOrNull() ?: return SeverityParse(default, null)
        return when (i) {
            SHACL.Info -> SeverityParse(ViolationSeverity.INFO, null)
            SHACL.Warning -> SeverityParse(ViolationSeverity.WARNING, null)
            SHACL.Violation -> SeverityParse(ViolationSeverity.VIOLATION, null)
            SHACL.Debug -> SeverityParse(ViolationSeverity.DEBUG, null)
            SHACL.Trace -> SeverityParse(ViolationSeverity.TRACE, null)
            else -> SeverityParse(ViolationSeverity.VIOLATION, i)
        }
    }

    private fun literalString(term: RdfTerm): String? =
        when (term) {
            is Literal -> term.lexical
            else -> null
        }

    private fun parseNonNegativeInt(term: RdfTerm, role: String): Int {
        val lit = term as? TypedLiteral ?: throw ShapeCompileException("$role expects xsd:integer, got $term")
        if (lit.datatype != XSD.integer && lit.datatype != XSD.nonNegativeInteger && lit.datatype != XSD.long) {
            throw ShapeCompileException("$role expects integer datatype, got ${lit.datatype}")
        }
        val v = lit.lexical.toIntOrNull() ?: throw ShapeCompileException("$role invalid lexical ${lit.lexical}")
        if (v < 0) throw ShapeCompileException("$role must be non-negative")
        return v
    }
}

internal fun constraintStub(type: ConstraintType, pathIri: Iri? = null, params: Map<String, Any> = emptyMap()) =
    ShaclConstraint(
        constraintType = type,
        path = pathIri?.value,
        parameters = params,
    )
