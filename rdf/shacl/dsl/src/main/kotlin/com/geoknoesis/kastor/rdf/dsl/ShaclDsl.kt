package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/**
 * DSL for creating SHACL shapes graphs.
 * Provides a type-safe, natural language syntax for defining SHACL constraints.
 *
 * Example:
 * ```kotlin
 * val shapesGraph = shacl {
 *     nodeShape("http://example.org/PersonShape") {
 *         targetClass(FOAF.Person)
 *
 *         property(FOAF.name) {
 *             minCount = 1
 *             maxCount = 1
 *             datatype = XSD.string
 *             minLength = 1
 *             maxLength = 100
 *         }
 *
 *         property(FOAF.age) {
 *             minCount = 0
 *             maxCount = 1
 *             datatype = XSD.integer
 *             minInclusive = 0
 *             maxInclusive = 150
 *         }
 *     }
 * }
 * ```
 *
 * Blank nodes created by the DSL (nested shapes, property shapes, RDF lists, ...) carry
 * labels that are unique per `ShaclDsl` instance, so shapes graphs built independently can
 * be merged without accidentally fusing distinct blank nodes.
 */
class ShaclDsl {
    private val graphDsl = GraphDsl()
    private var bnodeCounter = 0

    /** Random per-instance scope appended to every blank node label (collision-free across graphs). */
    private val bnodeScope = UUID.randomUUID().toString().replace("-", "")

    private fun nextBnode(prefix: String = "b"): BlankNode {
        return bnode("${prefix}${++bnodeCounter}_$bnodeScope")
    }

    /**
     * Create a node shape with the given IRI or QName.
     */
    fun nodeShape(shapeIri: String, configure: NodeShapeDsl.() -> Unit) {
        val shape = resolveIri(shapeIri)
        graphDsl.triple(shape, RDF.type, SHACL.NodeShape)
        val dsl = NodeShapeDsl(shape, graphDsl, ::nextBnode)
        dsl.configure()
    }

    /**
     * Create a property shape (standalone, not part of a node shape).
     */
    fun propertyShape(shapeIri: String, configure: PropertyShapeDsl.() -> Unit) {
        val shape = resolveIri(shapeIri)
        graphDsl.triple(shape, RDF.type, SHACL.PropertyShape)
        val dsl = PropertyShapeDsl(shape, graphDsl, ::nextBnode)
        dsl.configure()
    }

    /**
     * Configure prefix mappings for QName resolution.
     */
    fun prefixes(configure: MutableMap<String, String>.() -> Unit) {
        graphDsl.prefixes(configure)
    }

    /**
     * Add a single prefix mapping.
     */
    fun prefix(name: String, namespace: String) {
        graphDsl.prefix(name, namespace)
    }

    private fun resolveIri(iriOrQName: String): Iri {
        return graphDsl.qname(iriOrQName)
    }

    /**
     * Build the final RdfGraph from the collected triples.
     */
    fun build(): MutableRdfGraph {
        return graphDsl.build()
    }
}

// ---------------------------------------------------------------------------------------------
// Shared emission helpers
// ---------------------------------------------------------------------------------------------

private val SH_DECLARE = Iri("http://www.w3.org/ns/shacl#declare")
private val SH_PREFIX = Iri("http://www.w3.org/ns/shacl#prefix")
private val SH_NAMESPACE = Iri("http://www.w3.org/ns/shacl#namespace")

/**
 * Emits a well-formed RDF list (`rdf:first` / `rdf:rest` chain terminated by `rdf:nil`)
 * and returns its head together with the triples that were added.
 */
private fun GraphDsl.emitRdfList(
    values: List<RdfTerm>,
    nextBnode: (String) -> BlankNode,
): Pair<RdfTerm, List<RdfTriple>> {
    if (values.isEmpty()) return RDF.nil to emptyList()
    val added = mutableListOf<RdfTriple>()
    val head = nextBnode("list")
    var current = head
    values.forEachIndexed { index, element ->
        added += RdfTriple(current, RDF.first, element)
        if (index < values.size - 1) {
            val next = nextBnode("list")
            added += RdfTriple(current, RDF.rest, next)
            current = next
        } else {
            added += RdfTriple(current, RDF.rest, RDF.nil)
        }
    }
    triples.addAll(added)
    return head to added
}

/** Adds `subject predicate ( values... )` with a fresh RDF list. */
private fun GraphDsl.emitListValue(
    subject: RdfResource,
    predicate: Iri,
    values: List<RdfTerm>,
    nextBnode: (String) -> BlankNode,
) {
    val (head, _) = emitRdfList(values, nextBnode)
    triple(subject, predicate, head)
}

/**
 * Single-valued parameter slots: assigning a value replaces the triples previously emitted
 * for the same parameter (including RDF list cells); assigning `null` removes them.
 */
private class SingleValueSlots(
    private val subject: RdfResource,
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode,
) {
    private class Entry(val value: Any, val triples: List<RdfTriple>)

    private val entries = mutableMapOf<Iri, Entry>()

    fun get(predicate: Iri): Any? = entries[predicate]?.value

    fun set(predicate: Iri, value: Any?, obj: RdfTerm?) {
        clear(predicate)
        if (value == null || obj == null) return
        val t = RdfTriple(subject, predicate, obj)
        graphDsl.triples.add(t)
        entries[predicate] = Entry(value, listOf(t))
    }

    fun setList(predicate: Iri, value: Any?, items: List<RdfTerm>?) {
        clear(predicate)
        if (value == null || items == null) return
        val (head, listTriples) = graphDsl.emitRdfList(items, nextBnode)
        val t = RdfTriple(subject, predicate, head)
        graphDsl.triples.add(t)
        entries[predicate] = Entry(value, listTriples + t)
    }

    private fun clear(predicate: Iri) {
        entries.remove(predicate)?.triples?.forEach { graphDsl.triples.remove(it) }
    }
}

/**
 * Emits a SHACL-SPARQL constraint. Only SELECT queries are valid for `sh:SPARQLConstraint`
 * (`sh:select`); ASK queries belong to `sh:SPARQLAskValidator` in constraint components, and
 * CONSTRUCT / DESCRIBE are not used by SHACL-SPARQL constraints at all.
 */
private fun GraphDsl.emitSparqlConstraint(
    subject: RdfResource,
    query: SparqlQueryAst,
    nextBnode: (String) -> BlankNode,
    configure: SparqlConstraintDsl.() -> Unit,
) {
    require(query is SelectQueryAst) {
        "SHACL-SPARQL constraints (sh:SPARQLConstraint) only support SELECT queries (sh:select); " +
            "got ${query::class.simpleName}. ASK queries are only valid as sh:SPARQLAskValidator " +
            "of a constraint component; rewrite the check as a SELECT that returns one row per violation."
    }
    val sparqlConstraint = nextBnode("sparql")
    triple(subject, SHACL.sparql, sparqlConstraint)
    triple(sparqlConstraint, RDF.type, SHACL.SPARQLConstraint)
    triple(sparqlConstraint, SHACL.select, string(SparqlRenderer.render(query)))
    SparqlConstraintDsl(sparqlConstraint, this, nextBnode).configure()
}

private const val SPARQL_ASK_DEPRECATION =
    "sh:ask is not allowed on SHACL-SPARQL constraints (only sh:select is); this function never " +
        "produced a valid shapes graph and now throws IllegalArgumentException. Express the check as a " +
        "SELECT query that returns one row per violation and use sparql(configureQuery = { ... })."

/** Converts a Kotlin number to the matching XSD literal for range bounds. */
private fun Number.toBoundLiteral(): Literal = when (this) {
    is Int, is Long, is Short, is Byte -> Literal(this.toLong().toString(), XSD.integer)
    is BigInteger -> this.toLiteral()
    is BigDecimal -> this.toLiteral()
    is Double -> this.toLiteral()
    is Float -> this.toLiteral()
    else -> throw IllegalArgumentException(
        "Unsupported bound type ${this::class.qualifiedName}; use Int, Long, Short, Byte, BigInteger, " +
            "BigDecimal, Double, Float or pass a Literal"
    )
}

/**
 * Collects the operand shapes of a logical constraint (`sh:and`, `sh:or`, `sh:xone`).
 * The operands are emitted, in declaration order, as one RDF list.
 *
 * ```kotlin
 * orShapes {
 *     shape { property("ex:phone") { minCount = 1 } }
 *     shape { property("ex:mobile") { minCount = 1 } }
 *     shape("ex:EmailContactShape")
 * }
 * ```
 */
class ShapeListDsl internal constructor(
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode,
) {
    internal val operands = mutableListOf<RdfTerm>()

    /** Add an anonymous (blank node) node shape as the next operand. */
    fun shape(configure: NodeShapeDsl.() -> Unit) {
        val operand = nextBnode("shape")
        graphDsl.triple(operand, RDF.type, SHACL.NodeShape)
        NodeShapeDsl(operand, graphDsl, nextBnode).configure()
        operands += operand
    }

    /** Add a shape reference as the next operand. */
    fun shape(shapeRef: Iri) {
        operands += shapeRef
    }

    /** Add a shape reference (IRI or QName) as the next operand. */
    fun shape(shapeRef: String) {
        operands += graphDsl.qname(shapeRef)
    }
}

/**
 * DSL for configuring a SHACL NodeShape.
 */
class NodeShapeDsl(
    private val shape: RdfResource,
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode
) {
    private val slots = SingleValueSlots(shape, graphDsl, nextBnode)

    /**
     * Set the target class for this shape.
     */
    fun targetClass(targetClass: Iri) {
        graphDsl.triple(shape, SHACL.targetClass, targetClass)
    }

    /**
     * Set the target class for this shape using a string IRI or QName.
     */
    fun targetClass(targetClass: String) {
        val target = graphDsl.qname(targetClass)
        graphDsl.triple(shape, SHACL.targetClass, target)
    }

    /**
     * Add a target node.
     */
    fun targetNode(node: Iri) {
        graphDsl.triple(shape, SHACL.targetNode, node)
    }

    /**
     * Add a target node using a string IRI or QName.
     */
    fun targetNode(node: String) {
        val target = graphDsl.qname(node)
        graphDsl.triple(shape, SHACL.targetNode, target)
    }

    /**
     * Add target objects of a property.
     */
    fun targetObjectsOf(property: Iri) {
        graphDsl.triple(shape, SHACL.targetObjectsOf, property)
    }

    /**
     * Add target objects of a property using a string IRI or QName.
     */
    fun targetObjectsOf(property: String) {
        val prop = graphDsl.qname(property)
        graphDsl.triple(shape, SHACL.targetObjectsOf, prop)
    }

    /**
     * Add target subjects of a property.
     */
    fun targetSubjectsOf(property: Iri) {
        graphDsl.triple(shape, SHACL.targetSubjectsOf, property)
    }

    /**
     * Add target subjects of a property using a string IRI or QName.
     */
    fun targetSubjectsOf(property: String) {
        val prop = graphDsl.qname(property)
        graphDsl.triple(shape, SHACL.targetSubjectsOf, prop)
    }

    /**
     * Add a targetWhere constraint (SHACL 1.2).
     * This uses a node expression to dynamically compute target nodes.
     * The node expression is represented as a shape that contains the expression logic.
     */
    fun targetWhere(configure: NodeShapeDsl.() -> Unit) {
        val nodeExpr = nextBnode("targetWhere")
        graphDsl.triple(shape, SHACL.targetWhere, nodeExpr)
        graphDsl.triple(nodeExpr, RDF.type, SHACL.NodeShape)

        val dsl = NodeShapeDsl(nodeExpr, graphDsl, nextBnode)
        dsl.configure()
    }

    /**
     * Add a targetWhere constraint using a shape reference (SHACL 1.2).
     */
    fun targetWhere(shapeRef: Iri) {
        graphDsl.triple(shape, SHACL.targetWhere, shapeRef)
    }

    /**
     * Add a targetWhere constraint using a string IRI or QName (SHACL 1.2).
     */
    fun targetWhere(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(shape, SHACL.targetWhere, ref)
    }

    /**
     * Add a targetWhere constraint using a SPARQL SelectExpression (SHACL 1.2 SPARQL Extensions).
     */
    fun targetWhereSelect(configureQuery: SelectBuilder.() -> Unit) {
        val selectExpr = nextBnode("selectExpr")
        graphDsl.triple(shape, SHACL.targetWhere, selectExpr)
        graphDsl.triple(selectExpr, RDF.type, SHACL.SelectExpression)

        val builder = SelectBuilder(emptyList())
        builder.apply(configureQuery)
        val query = builder.build()
        val queryString = SparqlRenderer.render(query)
        graphDsl.triple(selectExpr, SHACL.selectExpression, string(queryString))
    }

    /**
     * Add a targetWhere constraint using a SPARQL expression (SHACL 1.2 SPARQL Extensions).
     */
    fun targetWhereExpr(expression: String) {
        val exprNode = nextBnode("exprExpr")
        graphDsl.triple(shape, SHACL.targetWhere, exprNode)
        graphDsl.triple(exprNode, RDF.type, SHACL.SPARQLExprExpression)
        graphDsl.triple(exprNode, SHACL.exprExpression, string(expression))
    }

    /**
     * Add an `sh:shape` **constraint** (SHACL 1.2): every focus/value node of this shape must
     * conform to [shapeRef], like `sh:node`.
     *
     * This is *not* a target declaration. In SHACL 1.2 a node is explicitly targeted by a shape
     * when the **data graph** contains `ex:node sh:shape ex:Shape`; to select focus nodes from the
     * shapes graph use [targetNode], [targetClass], [targetSubjectsOf], [targetObjectsOf] or
     * [targetWhere].
     */
    fun shape(shapeRef: Iri) {
        graphDsl.triple(shape, SHACL.shape, shapeRef)
    }

    /**
     * Add an `sh:shape` constraint using a string IRI or QName (SHACL 1.2). See [shape].
     */
    fun shape(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(shape, SHACL.shape, ref)
    }

    /**
     * Set whether this shape is closed (only allows declared properties).
     */
    fun closed(value: Boolean) {
        graphDsl.triple(shape, SHACL.closed, value.toLiteral())
    }

    /**
     * Set ignored properties for closed shapes, emitted as a single RDF list
     * (`sh:ignoredProperties ( p1 p2 ... )`). SHACL allows at most one value, so calling this
     * again replaces the previously set list.
     */
    fun ignoredProperties(properties: List<Iri>) {
        slots.setList(SHACL.ignoredProperties, properties.toList(), properties)
    }

    /**
     * Set ignored properties for closed shapes (vararg IRI/QName version). See [ignoredProperties].
     */
    fun ignoredProperties(vararg properties: String) {
        ignoredProperties(properties.map { graphDsl.qname(it) })
    }


    /**
     * Deactivate this shape.
     */
    fun deactivated(value: Boolean = true) {
        graphDsl.triple(shape, SHACL.deactivated, value.toLiteral())
    }

    /**
     * Add a property constraint to this node shape.
     */
    fun property(path: Iri, configure: PropertyShapeDsl.() -> Unit) {
        val propertyShape = nextBnode("property")
        graphDsl.triple(shape, SHACL.property, propertyShape)
        graphDsl.triple(propertyShape, RDF.type, SHACL.PropertyShape)

        val dsl = PropertyShapeDsl(propertyShape, graphDsl, nextBnode)
        dsl.path = path
        dsl.configure()
    }

    /**
     * Add a property constraint to this node shape using a string IRI or QName.
     */
    fun property(path: String, configure: PropertyShapeDsl.() -> Unit) {
        val pathIri = graphDsl.qname(path)
        property(pathIri, configure)
    }

    /**
     * Add a node constraint (reference to another shape).
     */
    fun node(shapeRef: Iri) {
        graphDsl.triple(shape, SHACL.node, shapeRef)
    }

    /**
     * Add a node constraint using a string IRI or QName.
     */
    fun node(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(shape, SHACL.node, ref)
    }

    /**
     * Add a nested node shape constraint.
     */
    fun node(configure: NodeShapeDsl.() -> Unit) {
        val nestedShape = nextBnode("node")
        graphDsl.triple(shape, SHACL.node, nestedShape)
        graphDsl.triple(nestedShape, RDF.type, SHACL.NodeShape)

        val dsl = NodeShapeDsl(nestedShape, graphDsl, nextBnode)
        dsl.configure()
    }

    private fun logical(predicate: Iri, operands: List<RdfTerm>) {
        graphDsl.emitListValue(shape, predicate, operands, nextBnode)
    }

    private fun logicalBlock(predicate: Iri, configure: ShapeListDsl.() -> Unit) {
        val list = ShapeListDsl(graphDsl, nextBnode)
        list.configure()
        logical(predicate, list.operands)
    }

    /**
     * Add a logical AND constraint with a **single** operand shape configured by the block:
     * emits `sh:and ( _:operand )`. Everything declared in the block belongs to that one operand
     * (so two `property` blocks inside mean "both hold"). To combine several operand shapes use
     * [andShapes].
     */
    fun and(configure: NodeShapeDsl.() -> Unit) = logicalBlock(SHACL.and) { shape(configure) }

    /**
     * Add a logical AND constraint over the given shapes: `sh:and ( s1 s2 ... )`.
     */
    fun and(shapes: List<Iri>) = logical(SHACL.and, shapes)

    /**
     * Add a logical AND constraint over shapes given as string IRIs or QNames.
     */
    fun and(vararg shapes: String) = logical(SHACL.and, shapes.map { graphDsl.qname(it) })

    /**
     * Add a logical AND constraint whose operands are collected by the block (see [ShapeListDsl]).
     */
    fun andShapes(configure: ShapeListDsl.() -> Unit) = logicalBlock(SHACL.and, configure)

    /**
     * Add a logical OR constraint with a **single** operand shape configured by the block:
     * emits `sh:or ( _:operand )`. Note that a single-operand OR is equivalent to that operand;
     * everything declared in the block must hold together. To express alternatives use [orShapes]:
     * `orShapes { shape { ... }; shape { ... } }`.
     */
    fun or(configure: NodeShapeDsl.() -> Unit) = logicalBlock(SHACL.or) { shape(configure) }

    /**
     * Add a logical OR constraint over the given shapes: `sh:or ( s1 s2 ... )`.
     */
    fun or(shapes: List<Iri>) = logical(SHACL.or, shapes)

    /**
     * Add a logical OR constraint over shapes given as string IRIs or QNames.
     */
    fun or(vararg shapes: String) = logical(SHACL.or, shapes.map { graphDsl.qname(it) })

    /**
     * Add a logical OR constraint whose operands are collected by the block (see [ShapeListDsl]).
     */
    fun orShapes(configure: ShapeListDsl.() -> Unit) = logicalBlock(SHACL.or, configure)

    /**
     * Add a logical XONE constraint with a **single** operand shape configured by the block:
     * emits `sh:xone ( _:operand )`. To express "exactly one of several shapes" use [xoneShapes].
     */
    fun xone(configure: NodeShapeDsl.() -> Unit) = logicalBlock(SHACL.xone) { shape(configure) }

    /**
     * Add a logical XONE constraint over the given shapes: `sh:xone ( s1 s2 ... )`.
     */
    fun xone(shapes: List<Iri>) = logical(SHACL.xone, shapes)

    /**
     * Add a logical XONE constraint over shapes given as string IRIs or QNames.
     */
    fun xone(vararg shapes: String) = logical(SHACL.xone, shapes.map { graphDsl.qname(it) })

    /**
     * Add a logical XONE constraint whose operands are collected by the block (see [ShapeListDsl]).
     */
    fun xoneShapes(configure: ShapeListDsl.() -> Unit) = logicalBlock(SHACL.xone, configure)

    /**
     * Add a logical NOT constraint.
     */
    fun not(configure: NodeShapeDsl.() -> Unit) {
        val notShape = nextBnode("not")
        graphDsl.triple(shape, SHACL.not, notShape)
        graphDsl.triple(notShape, RDF.type, SHACL.NodeShape)

        val dsl = NodeShapeDsl(notShape, graphDsl, nextBnode)
        dsl.configure()
    }

    /**
     * Add a logical NOT constraint with a shape reference.
     */
    fun not(shapeRef: Iri) {
        graphDsl.triple(shape, SHACL.not, shapeRef)
    }

    /**
     * Add a logical NOT constraint with a shape reference using a string IRI or QName.
     */
    fun not(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(shape, SHACL.not, ref)
    }

    /**
     * Set a custom message for this shape.
     */
    fun message(message: String) {
        graphDsl.triple(shape, SHACL.message, string(message))
    }

    /**
     * Set a custom message for this shape with language tag.
     */
    fun message(message: String, lang: String) {
        graphDsl.triple(shape, SHACL.message, lang(message, lang))
    }

    /**
     * Set the severity level for violations of this shape.
     */
    fun severity(severity: Severity) {
        graphDsl.triple(shape, SHACL.severity, severity.iri)
    }

    /**
     * Add a SPARQL-based constraint (SHACL-SPARQL). The query is rendered to a string and stored
     * as `sh:select`.
     *
     * @throws IllegalArgumentException if [query] is not a SELECT query (SHACL-SPARQL constraints
     *   only support `sh:select`).
     */
    fun sparql(query: SparqlQueryAst, configure: SparqlConstraintDsl.() -> Unit = {}) {
        graphDsl.emitSparqlConstraint(shape, query, nextBnode, configure)
    }

    /**
     * Add a SPARQL-based constraint using a SELECT query builder (SHACL-SPARQL).
     */
    fun sparql(configureQuery: SelectBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        val builder = SelectBuilder(emptyList())
        builder.apply(configureQuery)
        val query = builder.build()
        sparql(query, configureConstraint)
    }

    /**
     * Not supported: SHACL-SPARQL constraints cannot use ASK queries.
     *
     * @throws IllegalArgumentException always.
     */
    @Deprecated(SPARQL_ASK_DEPRECATION, level = DeprecationLevel.ERROR)
    fun sparqlAsk(configureQuery: AskBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        throw IllegalArgumentException(SPARQL_ASK_DEPRECATION)
    }
}

/**
 * DSL for configuring a SHACL PropertyShape.
 *
 * **Single-valued parameters.** The `var` properties (`minCount`, `datatype`, `pattern`, `in`, ...)
 * are single-valued slots: reading returns the last assigned value, assigning again **replaces** the
 * previously emitted triple(s) and assigning `null` removes them. This also applies to `sh:class`
 * and `sh:hasValue` when set through their `var`s, even though SHACL allows several values for
 * those parameters — use the additive functions [class] / [hasValue] to emit multiple values.
 */
class PropertyShapeDsl(
    private val propertyShape: RdfResource,
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode
) {
    private val slots = SingleValueSlots(propertyShape, graphDsl, nextBnode)

    /** The `sh:path` of this property shape (set automatically by `property(path) { }`). */
    var path: Iri?
        set(value) = slots.set(SHACL.path, value, value)
        get() = slots.get(SHACL.path) as Iri?

    /**
     * Set path using a string IRI or QName (replaces any previous path).
     */
    fun path(path: String) {
        this.path = graphDsl.qname(path)
    }

    // Cardinality constraints
    var minCount: Int?
        set(value) = slots.set(SHACL.minCount, value, value?.toLiteral())
        get() = slots.get(SHACL.minCount) as Int?

    var maxCount: Int?
        set(value) = slots.set(SHACL.maxCount, value, value?.toLiteral())
        get() = slots.get(SHACL.maxCount) as Int?

    // Type constraints
    var datatype: Iri?
        set(value) = slots.set(SHACL.datatype, value, value)
        get() = slots.get(SHACL.datatype) as Iri?

    /**
     * Set datatype using a string IRI or QName (replaces any previous datatype).
     */
    fun datatype(datatype: String) {
        this.datatype = graphDsl.qname(datatype)
    }

    /**
     * Single-valued `sh:class` slot (replace semantics). Use the [class] function to add several
     * `sh:class` values.
     */
    var `class`: Iri?
        set(value) = slots.set(SHACL.`class`, value, value)
        get() = slots.get(SHACL.`class`) as Iri?

    /**
     * Add an `sh:class` value using a string IRI or QName (additive; SHACL allows several values).
     */
    fun `class`(classIri: String) {
        val cls = graphDsl.qname(classIri)
        graphDsl.triple(propertyShape, SHACL.`class`, cls)
    }

    var nodeKind: NodeKind?
        set(value) = slots.set(SHACL.nodeKind, value, value?.iri)
        get() = slots.get(SHACL.nodeKind) as NodeKind?

    // String constraints
    var minLength: Int?
        set(value) = slots.set(SHACL.minLength, value, value?.toLiteral())
        get() = slots.get(SHACL.minLength) as Int?

    var maxLength: Int?
        set(value) = slots.set(SHACL.maxLength, value, value?.toLiteral())
        get() = slots.get(SHACL.maxLength) as Int?

    var pattern: String?
        set(value) = slots.set(SHACL.pattern, value, value?.let { string(it) })
        get() = slots.get(SHACL.pattern) as String?

    var flags: String?
        set(value) = slots.set(SHACL.flags, value, value?.let { string(it) })
        get() = slots.get(SHACL.flags) as String?

    /** `sh:languageIn`, emitted as an RDF list of language tags. */
    @Suppress("UNCHECKED_CAST")
    var languageIn: List<String>?
        set(value) = slots.setList(SHACL.languageIn, value?.toList(), value?.map { string(it) })
        get() = slots.get(SHACL.languageIn) as List<String>?

    var uniqueLang: Boolean?
        set(value) = slots.set(SHACL.uniqueLang, value, value?.toLiteral())
        get() = slots.get(SHACL.uniqueLang) as Boolean?

    /**
     * Shortcut for `sh:datatype rdf:dirLangString` (RDF 1.2). Use this on a
     * property whose values are directional language strings. Replaces any previous [datatype].
     */
    fun directionalLangString() {
        datatype = RDF.dirLangString
    }

    /**
     * Declares that values are directional language strings. **The base direction itself is not
     * constrained**: SHACL (Core 1.0/1.2) has no constraint on the base direction, and a literal's
     * lexical form never contains it, so no portable constraint can be emitted. This only emits
     * `sh:datatype rdf:dirLangString` (earlier versions also emitted an `sh:pattern` that rejected
     * every value). To enforce a specific direction, add a SHACL-SPARQL constraint supported by
     * your validator.
     */
    @Deprecated(
        "SHACL cannot constrain the base direction; this only sets sh:datatype rdf:dirLangString. " +
            "Use directionalLangString().",
        ReplaceWith("directionalLangString()"),
    )
    @Suppress("UNUSED_PARAMETER")
    fun languageDirection(direction: com.geoknoesis.kastor.rdf.Direction) {
        directionalLangString()
    }

    /**
     * Set singleLine constraint (SHACL 1.2).
     * Ensures that string values do not contain line breaks.
     */
    var singleLine: Boolean?
        set(value) = slots.set(SHACL.singleLine, value, value?.toLiteral())
        get() = slots.get(SHACL.singleLine) as Boolean?

    // Range constraints
    //
    // Numeric bounds accept any Kotlin number and emit the matching XSD datatype:
    // Int/Long/Short/Byte/BigInteger -> xsd:integer, BigDecimal -> xsd:decimal,
    // Double -> xsd:double, Float -> xsd:float. Non-numeric bounds (e.g. xsd:date) are set
    // with the Literal overloads, e.g. `minInclusive(LocalDate.of(2020, 1, 1).toLiteral())`;
    // the Number-typed getter returns null for such bounds.

    /** `sh:minInclusive` numeric bound (see the range constraints note above). */
    var minInclusive: Number?
        set(value) = slots.set(SHACL.minInclusive, value, value?.toBoundLiteral())
        get() = slots.get(SHACL.minInclusive) as? Number

    /** `sh:maxInclusive` numeric bound. */
    var maxInclusive: Number?
        set(value) = slots.set(SHACL.maxInclusive, value, value?.toBoundLiteral())
        get() = slots.get(SHACL.maxInclusive) as? Number

    /** `sh:minExclusive` numeric bound. */
    var minExclusive: Number?
        set(value) = slots.set(SHACL.minExclusive, value, value?.toBoundLiteral())
        get() = slots.get(SHACL.minExclusive) as? Number

    /** `sh:maxExclusive` numeric bound. */
    var maxExclusive: Number?
        set(value) = slots.set(SHACL.maxExclusive, value, value?.toBoundLiteral())
        get() = slots.get(SHACL.maxExclusive) as? Number

    /** Set `sh:minInclusive` to a literal bound (e.g. an `xsd:date`); replaces any previous bound. */
    fun minInclusive(value: Literal) = slots.set(SHACL.minInclusive, value, value)

    /** Set `sh:maxInclusive` to a literal bound; replaces any previous bound. */
    fun maxInclusive(value: Literal) = slots.set(SHACL.maxInclusive, value, value)

    /** Set `sh:minExclusive` to a literal bound; replaces any previous bound. */
    fun minExclusive(value: Literal) = slots.set(SHACL.minExclusive, value, value)

    /** Set `sh:maxExclusive` to a literal bound; replaces any previous bound. */
    fun maxExclusive(value: Literal) = slots.set(SHACL.maxExclusive, value, value)

    // Binary-compatibility bridges for the former Double-typed accessors.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("getMinInclusive")
    fun getMinInclusiveDouble(): Double? = minInclusive?.toDouble()

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("setMinInclusive")
    fun setMinInclusiveDouble(value: Double?) { minInclusive = value }

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("getMaxInclusive")
    fun getMaxInclusiveDouble(): Double? = maxInclusive?.toDouble()

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("setMaxInclusive")
    fun setMaxInclusiveDouble(value: Double?) { maxInclusive = value }

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("getMinExclusive")
    fun getMinExclusiveDouble(): Double? = minExclusive?.toDouble()

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("setMinExclusive")
    fun setMinExclusiveDouble(value: Double?) { minExclusive = value }

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("getMaxExclusive")
    fun getMaxExclusiveDouble(): Double? = maxExclusive?.toDouble()

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    @JvmName("setMaxExclusive")
    fun setMaxExclusiveDouble(value: Double?) { maxExclusive = value }

    /**
     * Emits `sh:totalDigits`. **Not a SHACL Core constraint** (it is an XSD facet that is not part
     * of SHACL 1.0/1.2 Core): SHACL Core validators, including Kastor's native validator, ignore it.
     * Kept for source compatibility; enforce digit limits with `pattern` or a SPARQL constraint.
     */
    @Deprecated("sh:totalDigits is not a SHACL Core constraint and is ignored by SHACL Core validators; use pattern or a SPARQL constraint")
    var totalDigits: Int?
        set(value) = slots.set(SHACL.totalDigits, value, value?.toLiteral())
        get() = slots.get(SHACL.totalDigits) as Int?

    /**
     * Emits `sh:fractionDigits`. **Not a SHACL Core constraint** (it is an XSD facet that is not part
     * of SHACL 1.0/1.2 Core): SHACL Core validators, including Kastor's native validator, ignore it.
     * Kept for source compatibility; enforce digit limits with `pattern` or a SPARQL constraint.
     */
    @Deprecated("sh:fractionDigits is not a SHACL Core constraint and is ignored by SHACL Core validators; use pattern or a SPARQL constraint")
    var fractionDigits: Int?
        set(value) = slots.set(SHACL.fractionDigits, value, value?.toLiteral())
        get() = slots.get(SHACL.fractionDigits) as Int?

    // Value constraints

    /**
     * Single-valued `sh:hasValue` slot (replace semantics). The `hasValue(...)` functions are
     * additive and may be called several times (SHACL allows several `sh:hasValue` values).
     */
    var hasValue: RdfTerm?
        set(value) = slots.set(SHACL.hasValue, value, value)
        get() = slots.get(SHACL.hasValue) as RdfTerm?

    /**
     * Add an `sh:hasValue` string value (additive).
     */
    fun hasValue(value: String) {
        graphDsl.triple(propertyShape, SHACL.hasValue, string(value))
    }

    /**
     * Add an `sh:hasValue` integer value (additive).
     */
    fun hasValue(value: Int) {
        graphDsl.triple(propertyShape, SHACL.hasValue, value.toLiteral())
    }

    /**
     * Add an `sh:hasValue` double value (additive).
     */
    fun hasValue(value: Double) {
        graphDsl.triple(propertyShape, SHACL.hasValue, value.toLiteral())
    }

    /**
     * Add an `sh:hasValue` IRI value (additive).
     */
    fun hasValue(value: Iri) {
        graphDsl.triple(propertyShape, SHACL.hasValue, value)
    }

    /** `sh:in`, emitted as a single RDF list. The `in(...)` functions share this slot. */
    @Suppress("UNCHECKED_CAST")
    var `in`: List<RdfTerm>?
        set(value) = slots.setList(SHACL.`in`, value?.toList(), value)
        get() = slots.get(SHACL.`in`) as List<RdfTerm>?

    /**
     * Set in constraint using a list of strings (replaces any previous `sh:in`).
     */
    fun `in`(vararg values: String) {
        this.`in` = values.map { string(it) }
    }

    /**
     * Set in constraint using a list of integers (replaces any previous `sh:in`).
     */
    fun `in`(vararg values: Int) {
        this.`in` = values.map { it.toLiteral() }
    }

    /**
     * Set in constraint using a list of doubles (replaces any previous `sh:in`).
     */
    fun `in`(vararg values: Double) {
        this.`in` = values.map { it.toLiteral() }
    }

    /**
     * Set in constraint using a list of IRIs (replaces any previous `sh:in`).
     */
    fun `in`(values: List<Iri>) {
        this.`in` = values.toList()
    }

    // Value comparison constraints
    fun equals(path: Iri) {
        graphDsl.triple(propertyShape, SHACL.equals, path)
    }

    fun equals(path: String) {
        val pathIri = graphDsl.qname(path)
        graphDsl.triple(propertyShape, SHACL.equals, pathIri)
    }

    fun disjoint(path: Iri) {
        graphDsl.triple(propertyShape, SHACL.disjoint, path)
    }

    fun disjoint(path: String) {
        val pathIri = graphDsl.qname(path)
        graphDsl.triple(propertyShape, SHACL.disjoint, pathIri)
    }

    fun lessThan(path: Iri) {
        graphDsl.triple(propertyShape, SHACL.lessThan, path)
    }

    fun lessThan(path: String) {
        val pathIri = graphDsl.qname(path)
        graphDsl.triple(propertyShape, SHACL.lessThan, pathIri)
    }

    fun lessThanOrEquals(path: Iri) {
        graphDsl.triple(propertyShape, SHACL.lessThanOrEquals, path)
    }

    fun lessThanOrEquals(path: String) {
        val pathIri = graphDsl.qname(path)
        graphDsl.triple(propertyShape, SHACL.lessThanOrEquals, pathIri)
    }

    // Qualified value shape constraints
    fun qualifiedValueShape(configure: NodeShapeDsl.() -> Unit) {
        val qualifiedShape = nextBnode("qualified")
        graphDsl.triple(propertyShape, SHACL.qualifiedValueShape, qualifiedShape)
        graphDsl.triple(qualifiedShape, RDF.type, SHACL.NodeShape)

        val dsl = NodeShapeDsl(qualifiedShape, graphDsl, nextBnode)
        dsl.configure()
    }

    fun qualifiedValueShape(shapeRef: Iri) {
        graphDsl.triple(propertyShape, SHACL.qualifiedValueShape, shapeRef)
    }

    fun qualifiedValueShape(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(propertyShape, SHACL.qualifiedValueShape, ref)
    }

    var qualifiedMinCount: Int?
        set(value) = slots.set(SHACL.qualifiedMinCount, value, value?.toLiteral())
        get() = slots.get(SHACL.qualifiedMinCount) as Int?

    var qualifiedMaxCount: Int?
        set(value) = slots.set(SHACL.qualifiedMaxCount, value, value?.toLiteral())
        get() = slots.get(SHACL.qualifiedMaxCount) as Int?

    var qualifiedValueShapesDisjoint: Boolean?
        set(value) = slots.set(SHACL.qualifiedValueShapesDisjoint, value, value?.toLiteral())
        get() = slots.get(SHACL.qualifiedValueShapesDisjoint) as Boolean?

    // Node constraint
    fun node(shapeRef: Iri) {
        graphDsl.triple(propertyShape, SHACL.node, shapeRef)
    }

    fun node(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(propertyShape, SHACL.node, ref)
    }

    fun node(configure: NodeShapeDsl.() -> Unit) {
        val nestedShape = nextBnode("node")
        graphDsl.triple(propertyShape, SHACL.node, nestedShape)
        graphDsl.triple(nestedShape, RDF.type, SHACL.NodeShape)

        val dsl = NodeShapeDsl(nestedShape, graphDsl, nextBnode)
        dsl.configure()
    }

    // Metadata
    fun name(name: String) {
        graphDsl.triple(propertyShape, SHACL.name, string(name))
    }

    fun name(name: String, lang: String) {
        graphDsl.triple(propertyShape, SHACL.name, lang(name, lang))
    }

    fun description(description: String) {
        graphDsl.triple(propertyShape, SHACL.description, string(description))
    }

    fun description(description: String, lang: String) {
        graphDsl.triple(propertyShape, SHACL.description, lang(description, lang))
    }

    fun order(order: Int) {
        graphDsl.triple(propertyShape, SHACL.order, order.toLiteral())
    }

    fun group(group: RdfResource) {
        graphDsl.triple(propertyShape, SHACL.group, group)
    }

    fun group(group: String) {
        val groupIri = graphDsl.qname(group)
        graphDsl.triple(propertyShape, SHACL.group, groupIri)
    }

    fun message(message: String) {
        graphDsl.triple(propertyShape, SHACL.message, string(message))
    }

    fun message(message: String, lang: String) {
        graphDsl.triple(propertyShape, SHACL.message, lang(message, lang))
    }

    fun severity(severity: Severity) {
        graphDsl.triple(propertyShape, SHACL.severity, severity.iri)
    }

    fun deactivated(value: Boolean = true) {
        graphDsl.triple(propertyShape, SHACL.deactivated, value.toLiteral())
    }

    /**
     * Set reifierShape constraint (SHACL 1.2).
     * Specifies a shape that must be satisfied by the reifier of an RDF-star triple.
     */
    fun reifierShape(shapeRef: Iri) {
        graphDsl.triple(propertyShape, SHACL.reifierShape, shapeRef)
    }

    /**
     * Set reifierShape constraint using a string IRI or QName (SHACL 1.2).
     */
    fun reifierShape(shapeRef: String) {
        val ref = graphDsl.qname(shapeRef)
        graphDsl.triple(propertyShape, SHACL.reifierShape, ref)
    }

    /**
     * Set reifierShape constraint with nested shape definition (SHACL 1.2).
     */
    fun reifierShape(configure: NodeShapeDsl.() -> Unit) {
        val reifierShapeNode = nextBnode("reifierShape")
        graphDsl.triple(propertyShape, SHACL.reifierShape, reifierShapeNode)
        graphDsl.triple(reifierShapeNode, RDF.type, SHACL.NodeShape)

        val dsl = NodeShapeDsl(reifierShapeNode, graphDsl, nextBnode)
        dsl.configure()
    }

    /**
     * Set reificationRequired constraint (SHACL 1.2).
     * If true, requires that RDF-star triples must have a reifier (annotated statement).
     */
    var reificationRequired: Boolean?
        set(value) = slots.set(SHACL.reificationRequired, value, value?.toLiteral())
        get() = slots.get(SHACL.reificationRequired) as Boolean?

    /**
     * Add a SPARQL-based constraint (SHACL-SPARQL), stored as `sh:select`.
     *
     * @throws IllegalArgumentException if [query] is not a SELECT query.
     */
    fun sparql(query: SparqlQueryAst, configure: SparqlConstraintDsl.() -> Unit = {}) {
        graphDsl.emitSparqlConstraint(propertyShape, query, nextBnode, configure)
    }

    /**
     * Add a SPARQL-based constraint using a SELECT query builder (SHACL-SPARQL).
     */
    fun sparql(configureQuery: SelectBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        val builder = SelectBuilder(emptyList())
        builder.apply(configureQuery)
        val query = builder.build()
        sparql(query, configureConstraint)
    }

    /**
     * Not supported: SHACL-SPARQL constraints cannot use ASK queries.
     *
     * @throws IllegalArgumentException always.
     */
    @Deprecated(SPARQL_ASK_DEPRECATION, level = DeprecationLevel.ERROR)
    fun sparqlAsk(configureQuery: AskBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        throw IllegalArgumentException(SPARQL_ASK_DEPRECATION)
    }
}

/**
 * SHACL node kind enumeration.
 */
enum class NodeKind(val iri: Iri) {
    IRI(SHACL.IRI),
    BlankNode(SHACL.BlankNode),
    Literal(SHACL.Literal),
    BlankNodeOrIRI(SHACL.BlankNodeOrIRI),
    BlankNodeOrLiteral(SHACL.BlankNodeOrLiteral),
    IRIOrLiteral(SHACL.IRIOrLiteral)
}

/**
 * SHACL severity enumeration.
 */
enum class Severity(val iri: Iri) {
    Violation(SHACL.Violation),
    Warning(SHACL.Warning),
    Info(SHACL.Info)
}

/**
 * DSL for configuring a SHACL SPARQL constraint (SHACL 1.2 SPARQL Extensions).
 */
class SparqlConstraintDsl(
    private val sparqlConstraint: RdfResource,
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode
) {
    /**
     * Add prefix declarations for the SPARQL query, emitted as
     * `sh:prefixes [ sh:declare [ sh:prefix "p" ; sh:namespace "ns"^^xsd:anyURI ] , ... ]`.
     */
    fun prefixes(configure: MutableMap<String, String>.() -> Unit) {
        val prefixMap = linkedMapOf<String, String>()
        prefixMap.configure()
        if (prefixMap.isEmpty()) return

        val declarations = nextBnode("prefixes")
        graphDsl.triple(sparqlConstraint, SHACL.prefixes, declarations)
        prefixMap.forEach { (prefix, namespace) ->
            val decl = nextBnode("declare")
            graphDsl.triple(declarations, SH_DECLARE, decl)
            graphDsl.triple(decl, SH_PREFIX, string(prefix))
            graphDsl.triple(decl, SH_NAMESPACE, Literal(namespace, XSD.anyURI))
        }
    }

    /**
     * Add a parameter declaration.
     */
    fun parameter(parameter: Iri) {
        graphDsl.triple(sparqlConstraint, SHACL.parameter, parameter)
    }

    /**
     * Add a parameter declaration using a string IRI or QName.
     */
    fun parameter(parameter: String) {
        val param = graphDsl.qname(parameter)
        graphDsl.triple(sparqlConstraint, SHACL.parameter, param)
    }

    /**
     * Set a label template for validation results.
     */
    fun labelTemplate(template: String) {
        graphDsl.triple(sparqlConstraint, SHACL.labelTemplate, string(template))
    }

    /**
     * Set a label template with language tag.
     */
    fun labelTemplate(template: String, lang: String) {
        graphDsl.triple(sparqlConstraint, SHACL.labelTemplate, lang(template, lang))
    }

    /**
     * Set a custom message for this constraint.
     */
    fun message(message: String) {
        graphDsl.triple(sparqlConstraint, SHACL.message, string(message))
    }

    /**
     * Set a custom message with language tag.
     */
    fun message(message: String, lang: String) {
        graphDsl.triple(sparqlConstraint, SHACL.message, lang(message, lang))
    }

    /**
     * Set the severity level for violations of this constraint.
     */
    fun severity(severity: Severity) {
        graphDsl.triple(sparqlConstraint, SHACL.severity, severity.iri)
    }

    /**
     * Deactivate this constraint.
     */
    fun deactivated(value: Boolean = true) {
        graphDsl.triple(sparqlConstraint, SHACL.deactivated, value.toLiteral())
    }
}

/**
 * Create a SHACL shapes graph using the DSL.
 *
 * Example:
 * ```kotlin
 * val shapesGraph = shacl {
 *     nodeShape("http://example.org/PersonShape") {
 *         targetClass(FOAF.Person)
 *         property(FOAF.name) {
 *             minCount = 1
 *             datatype = XSD.string
 *         }
 *     }
 * }
 * ```
 */
fun shacl(configure: ShaclDsl.() -> Unit): MutableRdfGraph {
    val dsl = ShaclDsl()
    dsl.configure()
    return dsl.build()
}
