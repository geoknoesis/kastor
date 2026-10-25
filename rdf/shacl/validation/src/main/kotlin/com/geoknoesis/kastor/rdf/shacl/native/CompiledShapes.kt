package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
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
        /**
         * The parameters whose value is a node expression (SHACL 1.2 Node Expressions §8), by name, evaluated per focus
         * node and pre-bound with the constant [parameters]; a computed parameter is not a key of [parameters].
         */
        val computed: Map<String, ComputedComponentParameter> = emptyMap(),
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

    /**
     * A constraint of [component] with at least one parameter computed by a node expression (SHACL 1.2 Node Expressions
     * §8, Dynamic SHACL): per focus node, the engine evaluates the expressions in the data graph with the focus node and
     * an empty scope, and evaluates the constant constraint [ConstraintInstantiation] builds from the outputs. Absent
     * optional parameters are not keys of [parameters].
     */
    data class Dynamic(
        val component: DynamicComponent,
        val parameters: Map<Iri, ParameterValue>,
        val extras: ComponentExtras = ComponentExtras(),
    ) : PropertyConstraint() {
        /** The computed parameters' expressions, in parameter order. */
        val expressions: List<NodeExpression> get() = parameters.values.mapNotNull { (it as? ParameterValue.Computed)?.expr }
    }

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
    /**
     * A computed `sh:closed` (SHACL 1.2 Node Expressions §8), resolved per focus node to a [ClosedMode]; [closed] is then
     * [ClosedMode.NONE].
     */
    val closedParameter: ParameterValue.Computed? = null,
    /**
     * A computed `sh:ignoredProperties` (§8), whose outputs are ignored besides [ignoredProperties]; only when the shape
     * may be closed ([closed] or [closedParameter]).
     */
    val ignoredParameter: ParameterValue.Computed? = null,
    /** A computed `sh:uniqueValuesFor` (§8), resolved per focus node; [uniqueValuesForProps] is then empty. */
    val uniqueValuesForParameter: ParameterValue.Computed? = null,
)

/**
 * Compiled shapes graph. Every shape reachable from a top-level shape (via `sh:node`, `sh:and`/`sh:or`/`sh:xone`/
 * `sh:not`, `sh:qualifiedValueShape`, `sh:shape`, `sh:someValue`, `sh:memberShape`, `sh:reifierShape`,
 * `sh:nodeByExpression`, `sh:targetWhere`, `sh:property`, and the shapes of the node expressions at `sh:values`,
 * `sh:defaultValue`, `sh:targetNode`, `sh:expression`, `sh:nodeByExpression` and the computed constraint parameters
 * of Dynamic SHACL, Node Expressions §8, which include those of SPARQL-based constraint components
 * ([NodeExpressions.shapeRefs])) is
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
     * Shapes that only a computed shape reference can name (R8: the candidates of [ExpressionShapes])
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

internal fun constraintStub(type: ConstraintType, pathIri: Iri? = null, params: Map<String, Any> = emptyMap()) =
    ShaclConstraint(
        constraintType = type,
        path = pathIri?.value,
        parameters = params,
    )
