package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * DSL for creating SHACL shapes graphs.
 * Provides a type-safe, natural language syntax for defining SHACL constraints.
 * 
 * Example:
 * ```kotlin
 * val shapesGraph = shacl {
 *     nodeShape("PersonShape") {
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
 */
class ShaclDsl {
    private val graphDsl = GraphDsl()
    private var bnodeCounter = 0
    
    private fun nextBnode(prefix: String = "b"): BlankNode {
        return bnode("${prefix}${++bnodeCounter}")
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

/**
 * DSL for configuring a SHACL NodeShape.
 */
class NodeShapeDsl(
    private val shape: RdfResource,
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode
) {
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
     * Add an explicit shape target (SHACL 1.2).
     * This allows shapes to be explicitly applied to nodes via sh:shape.
     */
    fun shape(shapeRef: Iri) {
        graphDsl.triple(shape, SHACL.shape, shapeRef)
    }
    
    /**
     * Add an explicit shape target using a string IRI or QName (SHACL 1.2).
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
     * Set ignored properties for closed shapes.
     */
    fun ignoredProperties(properties: List<Iri>) {
        properties.forEach { prop ->
            graphDsl.triple(shape, SHACL.ignoredProperties, prop)
        }
    }
    
    /**
     * Set ignored properties for closed shapes (vararg version).
     */
    fun ignoredProperties(vararg properties: String) {
        properties.forEach { propStr ->
            val prop = graphDsl.qname(propStr)
            graphDsl.triple(shape, SHACL.ignoredProperties, prop)
        }
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
        graphDsl.triple(propertyShape, SHACL.path, path)
        
        val dsl = PropertyShapeDsl(propertyShape, graphDsl, nextBnode)
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
    
    /**
     * Add a logical AND constraint.
     */
    fun and(configure: NodeShapeDsl.() -> Unit) {
        val andShape = nextBnode("and")
        graphDsl.triple(shape, SHACL.and, andShape)
        graphDsl.triple(andShape, RDF.type, SHACL.NodeShape)
        
        val dsl = NodeShapeDsl(andShape, graphDsl, nextBnode)
        dsl.configure()
    }
    
    /**
     * Add a logical AND constraint with multiple shapes.
     */
    fun and(shapes: List<Iri>) {
        shapes.forEach { shapeRef ->
            graphDsl.triple(shape, SHACL.and, shapeRef)
        }
    }
    
    /**
     * Add a logical AND constraint with multiple shapes using string IRIs or QNames.
     */
    fun and(vararg shapes: String) {
        shapes.forEach { shapeStr ->
            val shapeRef = graphDsl.qname(shapeStr)
            graphDsl.triple(shape, SHACL.and, shapeRef)
        }
    }
    
    /**
     * Add a logical OR constraint.
     */
    fun or(configure: NodeShapeDsl.() -> Unit) {
        val orShape = nextBnode("or")
        graphDsl.triple(shape, SHACL.or, orShape)
        graphDsl.triple(orShape, RDF.type, SHACL.NodeShape)
        
        val dsl = NodeShapeDsl(orShape, graphDsl, nextBnode)
        dsl.configure()
    }
    
    /**
     * Add a logical OR constraint with multiple shapes.
     */
    fun or(shapes: List<Iri>) {
        shapes.forEach { shapeRef ->
            graphDsl.triple(shape, SHACL.or, shapeRef)
        }
    }
    
    /**
     * Add a logical OR constraint with multiple shapes using string IRIs or QNames.
     */
    fun or(vararg shapes: String) {
        shapes.forEach { shapeStr ->
            val shapeRef = graphDsl.qname(shapeStr)
            graphDsl.triple(shape, SHACL.or, shapeRef)
        }
    }
    
    /**
     * Add a logical XONE (exactly one) constraint.
     */
    fun xone(configure: NodeShapeDsl.() -> Unit) {
        val xoneShape = nextBnode("xone")
        graphDsl.triple(shape, SHACL.xone, xoneShape)
        graphDsl.triple(xoneShape, RDF.type, SHACL.NodeShape)
        
        val dsl = NodeShapeDsl(xoneShape, graphDsl, nextBnode)
        dsl.configure()
    }
    
    /**
     * Add a logical XONE constraint with multiple shapes.
     */
    fun xone(shapes: List<Iri>) {
        shapes.forEach { shapeRef ->
            graphDsl.triple(shape, SHACL.xone, shapeRef)
        }
    }
    
    /**
     * Add a logical XONE constraint with multiple shapes using string IRIs or QNames.
     */
    fun xone(vararg shapes: String) {
        shapes.forEach { shapeStr ->
            val shapeRef = graphDsl.qname(shapeStr)
            graphDsl.triple(shape, SHACL.xone, shapeRef)
        }
    }
    
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
     * Add a SPARQL-based constraint (SHACL 1.2 SPARQL Extensions).
     * The SPARQL query is rendered to a string and stored as a literal.
     */
    fun sparql(query: SparqlQueryAst, configure: SparqlConstraintDsl.() -> Unit = {}) {
        val sparqlConstraint = nextBnode("sparql")
        graphDsl.triple(shape, SHACL.sparql, sparqlConstraint)
        graphDsl.triple(sparqlConstraint, RDF.type, SHACL.SPARQLConstraint)
        
        // Render SPARQL query to string and store in appropriate property
        val queryString = SparqlRenderer.render(query)
        when (query) {
            is SelectQueryAst -> {
                graphDsl.triple(sparqlConstraint, SHACL.select, string(queryString))
            }
            is AskQueryAst -> {
                graphDsl.triple(sparqlConstraint, SHACL.ask, string(queryString))
            }
            else -> {
                // Default to SELECT for other query types
                graphDsl.triple(sparqlConstraint, SHACL.select, string(queryString))
            }
        }
        
        val dsl = SparqlConstraintDsl(sparqlConstraint, graphDsl, nextBnode)
        dsl.configure()
    }
    
    /**
     * Add a SPARQL-based constraint using a SELECT query builder (SHACL 1.2 SPARQL Extensions).
     */
    fun sparql(configureQuery: SelectBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        val builder = SelectBuilder(emptyList())
        builder.apply(configureQuery)
        val query = builder.build()
        sparql(query, configureConstraint)
    }
    
    /**
     * Add a SPARQL-based constraint using an ASK query builder (SHACL 1.2 SPARQL Extensions).
     */
    fun sparqlAsk(configureQuery: AskBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        val builder = AskBuilder()
        builder.apply(configureQuery)
        val query = builder.build()
        sparql(query, configureConstraint)
    }
}

/**
 * DSL for configuring a SHACL PropertyShape.
 */
class PropertyShapeDsl(
    private val propertyShape: RdfResource,
    private val graphDsl: GraphDsl,
    private val nextBnode: (String) -> BlankNode
) {
    // Path (can be set for standalone property shapes)
    var path: Iri?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.path, it)
            }
        }
        get() = null
    
    /**
     * Set path using a string IRI or QName.
     */
    fun path(path: String) {
        val pathIri = graphDsl.qname(path)
        graphDsl.triple(propertyShape, SHACL.path, pathIri)
    }
    
    // Cardinality constraints
    var minCount: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.minCount, it.toLiteral())
            }
        }
        get() = null // Not retrievable, only settable
    
    var maxCount: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.maxCount, it.toLiteral())
            }
        }
        get() = null
    
    // Type constraints
    var datatype: Iri?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.datatype, it)
            }
        }
        get() = null
    
    /**
     * Set datatype using a string IRI or QName.
     */
    fun datatype(datatype: String) {
        val dt = graphDsl.qname(datatype)
        graphDsl.triple(propertyShape, SHACL.datatype, dt)
    }
    
    var `class`: Iri?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.`class`, it)
            }
        }
        get() = null
    
    /**
     * Set class using a string IRI or QName.
     */
    fun `class`(classIri: String) {
        val cls = graphDsl.qname(classIri)
        graphDsl.triple(propertyShape, SHACL.`class`, cls)
    }
    
    var nodeKind: NodeKind?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.nodeKind, it.iri)
            }
        }
        get() = null
    
    // String constraints
    var minLength: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.minLength, it.toLiteral())
            }
        }
        get() = null
    
    var maxLength: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.maxLength, it.toLiteral())
            }
        }
        get() = null
    
    var pattern: String?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.pattern, string(it))
            }
        }
        get() = null
    
    var flags: String?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.flags, string(it))
            }
        }
        get() = null
    
    var languageIn: List<String>?
        set(value) {
            value?.let { tags ->
                val list = createRdfList(tags.map { string(it) })
                graphDsl.triple(propertyShape, SHACL.languageIn, list)
            }
        }
        get() = null
    
    var uniqueLang: Boolean?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.uniqueLang, it.toLiteral())
            }
        }
        get() = null
    
    /**
     * Set singleLine constraint (SHACL 1.2).
     * Ensures that string values do not contain line breaks.
     */
    var singleLine: Boolean?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.singleLine, it.toLiteral())
            }
        }
        get() = null
    
    // Numeric constraints
    var minInclusive: Double?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.minInclusive, it.toLiteral())
            }
        }
        get() = null
    
    var maxInclusive: Double?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.maxInclusive, it.toLiteral())
            }
        }
        get() = null
    
    var minExclusive: Double?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.minExclusive, it.toLiteral())
            }
        }
        get() = null
    
    var maxExclusive: Double?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.maxExclusive, it.toLiteral())
            }
        }
        get() = null
    
    var totalDigits: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.totalDigits, it.toLiteral())
            }
        }
        get() = null
    
    var fractionDigits: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.fractionDigits, it.toLiteral())
            }
        }
        get() = null
    
    // Value constraints
    var hasValue: RdfTerm?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.hasValue, it)
            }
        }
        get() = null
    
    /**
     * Set hasValue using a string.
     */
    fun hasValue(value: String) {
        graphDsl.triple(propertyShape, SHACL.hasValue, string(value))
    }
    
    /**
     * Set hasValue using an integer.
     */
    fun hasValue(value: Int) {
        graphDsl.triple(propertyShape, SHACL.hasValue, value.toLiteral())
    }
    
    /**
     * Set hasValue using a double.
     */
    fun hasValue(value: Double) {
        graphDsl.triple(propertyShape, SHACL.hasValue, value.toLiteral())
    }
    
    /**
     * Set hasValue using an IRI.
     */
    fun hasValue(value: Iri) {
        graphDsl.triple(propertyShape, SHACL.hasValue, value)
    }
    
    var `in`: List<RdfTerm>?
        set(value) {
            value?.let { values ->
                val list = createRdfList(values)
                graphDsl.triple(propertyShape, SHACL.`in`, list)
            }
        }
        get() = null
    
    /**
     * Set in constraint using a list of strings.
     */
    fun `in`(vararg values: String) {
        val list = createRdfList(values.map { string(it) })
        graphDsl.triple(propertyShape, SHACL.`in`, list)
    }
    
    /**
     * Set in constraint using a list of integers.
     */
    fun `in`(vararg values: Int) {
        val list = createRdfList(values.map { it.toLiteral() })
        graphDsl.triple(propertyShape, SHACL.`in`, list)
    }
    
    /**
     * Set in constraint using a list of doubles.
     */
    fun `in`(vararg values: Double) {
        val list = createRdfList(values.map { it.toLiteral() })
        graphDsl.triple(propertyShape, SHACL.`in`, list)
    }
    
    /**
     * Set in constraint using a list of IRIs.
     */
    fun `in`(values: List<Iri>) {
        val list = createRdfList(values.map { it })
        graphDsl.triple(propertyShape, SHACL.`in`, list)
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
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.qualifiedMinCount, it.toLiteral())
            }
        }
        get() = null
    
    var qualifiedMaxCount: Int?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.qualifiedMaxCount, it.toLiteral())
            }
        }
        get() = null
    
    var qualifiedValueShapesDisjoint: Boolean?
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.qualifiedValueShapesDisjoint, it.toLiteral())
            }
        }
        get() = null
    
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
        set(value) {
            value?.let {
                graphDsl.triple(propertyShape, SHACL.reificationRequired, it.toLiteral())
            }
        }
        get() = null
    
    /**
     * Add a SPARQL-based constraint (SHACL 1.2 SPARQL Extensions).
     */
    fun sparql(query: SparqlQueryAst, configure: SparqlConstraintDsl.() -> Unit = {}) {
        val sparqlConstraint = nextBnode("sparql")
        graphDsl.triple(propertyShape, SHACL.sparql, sparqlConstraint)
        graphDsl.triple(sparqlConstraint, RDF.type, SHACL.SPARQLConstraint)
        
        // Render SPARQL query to string and store in appropriate property
        val queryString = SparqlRenderer.render(query)
        when (query) {
            is SelectQueryAst -> {
                graphDsl.triple(sparqlConstraint, SHACL.select, string(queryString))
            }
            is AskQueryAst -> {
                graphDsl.triple(sparqlConstraint, SHACL.ask, string(queryString))
            }
            else -> {
                // Default to SELECT for other query types
                graphDsl.triple(sparqlConstraint, SHACL.select, string(queryString))
            }
        }
        
        val dsl = SparqlConstraintDsl(sparqlConstraint, graphDsl, nextBnode)
        dsl.configure()
    }
    
    /**
     * Add a SPARQL-based constraint using a SELECT query builder (SHACL 1.2 SPARQL Extensions).
     */
    fun sparql(configureQuery: SelectBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        val builder = SelectBuilder(emptyList())
        builder.apply(configureQuery)
        val query = builder.build()
        sparql(query, configureConstraint)
    }
    
    /**
     * Add a SPARQL-based constraint using an ASK query builder (SHACL 1.2 SPARQL Extensions).
     */
    fun sparqlAsk(configureQuery: AskBuilder.() -> Unit, configureConstraint: SparqlConstraintDsl.() -> Unit = {}) {
        val builder = AskBuilder()
        builder.apply(configureQuery)
        val query = builder.build()
        sparql(query, configureConstraint)
    }
    
    // Helper to create RDF lists
    private fun createRdfList(values: List<RdfTerm>): RdfTerm {
        if (values.isEmpty()) return RDF.nil
        
        val listHead = nextBnode("list")
        var currentNode = listHead
        
        values.forEachIndexed { index, element ->
            graphDsl.triple(currentNode, RDF.first, element)
            if (index < values.size - 1) {
                val nextNode = nextBnode("list")
                graphDsl.triple(currentNode, RDF.rest, nextNode)
                currentNode = nextNode
            } else {
                graphDsl.triple(currentNode, RDF.rest, RDF.nil)
            }
        }
        
        return listHead
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
     * Add prefix declarations for the SPARQL query.
     */
    fun prefixes(configure: MutableMap<String, String>.() -> Unit) {
        val prefixMap = mutableMapOf<String, String>()
        prefixMap.configure()
        
        prefixMap.forEach { (prefix, namespace) ->
            val prefixDecl = nextBnode("prefix")
            graphDsl.triple(sparqlConstraint, SHACL.prefixes, prefixDecl)
            graphDsl.triple(prefixDecl, RDF.first, string(prefix))
            val rest = nextBnode("prefixRest")
            graphDsl.triple(prefixDecl, RDF.rest, rest)
            graphDsl.triple(rest, RDF.first, string(namespace))
            graphDsl.triple(rest, RDF.rest, RDF.nil)
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
 *     nodeShape("PersonShape") {
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

