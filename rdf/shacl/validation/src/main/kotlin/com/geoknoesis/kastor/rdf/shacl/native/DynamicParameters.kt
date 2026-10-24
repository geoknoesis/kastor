package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException

/**
 * The value of a constraint parameter of a shape (SHACL 1.2 Node Expressions §8, Dynamic SHACL): a [Constant], or a
 * node expression [Computed] per focus node from the blank node [Computed.node].
 */
internal sealed interface ParameterValue {
    data class Constant(val term: RdfTerm) : ParameterValue
    data class Computed(val node: BlankNode, val expr: NodeExpression) : ParameterValue
}

/**
 * How the output nodes of a computed parameter make its value: exactly one node ([SINGLE]); the members of a list
 * that may also be a single IRI, so at least one node ([IRI_OR_LIST]); the members of a list, possibly empty ([LIST]).
 */
internal enum class ParameterShape { SINGLE, IRI_OR_LIST, LIST }

/**
 * The Core constraint components whose parameters may be computed (§8), with their parameters and how the outputs of
 * a computed value make the value of each ([ParameterShape]).
 */
internal enum class DynamicComponent(val type: ConstraintType, val parameters: List<Iri>, val shape: ParameterShape = ParameterShape.SINGLE) {
    MIN_COUNT(ConstraintType.MIN_COUNT, listOf(SHACL.minCount)),
    MAX_COUNT(ConstraintType.MAX_COUNT, listOf(SHACL.maxCount)),
    MIN_LENGTH(ConstraintType.MIN_LENGTH, listOf(SHACL.minLength)),
    MAX_LENGTH(ConstraintType.MAX_LENGTH, listOf(SHACL.maxLength)),
    MIN_INCLUSIVE(ConstraintType.MIN_INCLUSIVE, listOf(SHACL.minInclusive)),
    MAX_INCLUSIVE(ConstraintType.MAX_INCLUSIVE, listOf(SHACL.maxInclusive)),
    MIN_EXCLUSIVE(ConstraintType.MIN_EXCLUSIVE, listOf(SHACL.minExclusive)),
    MAX_EXCLUSIVE(ConstraintType.MAX_EXCLUSIVE, listOf(SHACL.maxExclusive)),
    UNIQUE_LANG(ConstraintType.UNIQUE_LANG, listOf(SHACL.uniqueLang)),
    SINGLE_LINE(ConstraintType.SINGLE_LINE, listOf(SHACL.singleLine)),
    MIN_LIST_LENGTH(ConstraintType.MIN_LIST_LENGTH, listOf(SHACL.minListLength)),
    MAX_LIST_LENGTH(ConstraintType.MAX_LIST_LENGTH, listOf(SHACL.maxListLength)),
    UNIQUE_MEMBERS(ConstraintType.UNIQUE_MEMBERS, listOf(SHACL.uniqueMembers)),
    CLASS(ConstraintType.CLASS, listOf(SHACL.`class`), ParameterShape.IRI_OR_LIST),
    DATATYPE(ConstraintType.DATATYPE, listOf(SHACL.datatype), ParameterShape.IRI_OR_LIST),
    NODE_KIND(ConstraintType.NODE_KIND, listOf(SHACL.nodeKind), ParameterShape.IRI_OR_LIST),
    ROOT_CLASS(ConstraintType.ROOT_CLASS, listOf(SHACL.rootClass), ParameterShape.IRI_OR_LIST),
    IN(ConstraintType.IN, listOf(SHACL.`in`), ParameterShape.LIST),
    LANGUAGE_IN(ConstraintType.LANGUAGE_IN, listOf(SHACL.languageIn), ParameterShape.LIST),
}

/** Recognises computed parameter values (SHACL 1.2 Node Expressions §8). */
internal object DynamicParameters {
    private val shapes: Map<Iri, ParameterShape> by lazy {
        DynamicComponent.entries.flatMap { c -> c.parameters.map { it to c.shape } }.toMap()
    }

    /** How the outputs of a computed value of [predicate] make its value; null when [predicate] cannot be computed. */
    fun shapeOf(predicate: Iri): ParameterShape? = shapes[predicate]

    /** Every predicate whose value may be computed. */
    val predicates: Set<Iri> get() = shapes.keys

    /**
     * The value [term] of the eligible parameter [predicate] of the shape [subject]: an IRI, a literal or a triple term
     * is a constant, and so is a blank node that is an RDF list at a list-taking parameter. Any other blank node is a
     * node expression; one that is no node expression at all makes the shapes graph ill-formed.
     */
    fun value(subject: RdfResource, predicate: Iri, term: RdfTerm, index: ShapeGraphIndex): ParameterValue {
        requireNotNull(shapeOf(predicate)) { "$predicate is not a dynamic parameter" }
        if (term !is BlankNode || isConstantList(predicate, term, index)) return ParameterValue.Constant(term)
        val expr = NodeExpressions.compile(term, index)
            ?: throw ShapeCompileException(
                "${displayName(predicate)} value $term of shape $subject is neither a constant nor a node expression (SHACL 1.2 Node Expressions §8)",
            )
        return ParameterValue.Computed(term, expr)
    }

    /**
     * True when [term] is a constant SHACL list at the list-taking parameter [predicate] (D4 rule 2): a blank node with
     * an `rdf:first` or `rdf:rest` triple. Its members are parsed as ever, so a malformed list stays a compile error.
     */
    fun isConstantList(predicate: Iri, term: RdfTerm, index: ShapeGraphIndex): Boolean =
        term is BlankNode && shapeOf(predicate).let { it != null && it != ParameterShape.SINGLE } &&
            (index.objects(term, RDF.first).isNotEmpty() || index.objects(term, RDF.rest).isNotEmpty())

    /** `sh:minCount` for a SHACL parameter, else the IRI. */
    fun displayName(predicate: Iri): String =
        if (predicate.value.startsWith(SHACL.namespace)) "sh:" + predicate.value.removePrefix(SHACL.namespace) else predicate.value
}
