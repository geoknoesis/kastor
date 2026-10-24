package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

/** What a constraint takes from its shape besides its parameter values. */
internal data class ComponentExtras(
    val severityOverride: ViolationSeverity? = null,
    val messages: List<Literal> = emptyList(),
    val qualifiedShape: RdfResource? = null,
    val siblings: List<RdfResource> = emptyList(),
    val reifierShape: RdfResource? = null,
)

/**
 * Builds the constant constraint of a [DynamicComponent] from its parameter values: the shapes compiler, for constant
 * values, and the engine, per focus node, for the values a node expression computes (SHACL 1.2 Node Expressions §8).
 */
internal object ConstraintInstantiation {

    /** Builds the constant constraint; throws ShapeCompileException on an ill-typed value. Null = no constraint. */
    @Suppress("UNUSED_PARAMETER")
    fun instantiate(component: DynamicComponent, values: Map<Iri, List<RdfTerm>>, extras: ComponentExtras, config: ValidationConfig): PropertyConstraint? {
        fun single(): RdfTerm = values[component.parameters.single()]?.singleOrNull()
            ?: throw ShapeCompileException("${DynamicParameters.displayName(component.parameters.single())} needs exactly one value")
        return when (component) {
            DynamicComponent.MIN_COUNT -> PropertyConstraint.MinCount(parseNonNegativeInt(single(), "sh:minCount"))
            DynamicComponent.MAX_COUNT -> PropertyConstraint.MaxCount(parseNonNegativeInt(single(), "sh:maxCount"))
            DynamicComponent.MIN_LENGTH -> PropertyConstraint.MinLength(parseNonNegativeInt(single(), "sh:minLength"))
            DynamicComponent.MAX_LENGTH -> PropertyConstraint.MaxLength(parseNonNegativeInt(single(), "sh:maxLength"))
            DynamicComponent.MIN_INCLUSIVE -> PropertyConstraint.MinInclusive(single())
            DynamicComponent.MAX_INCLUSIVE -> PropertyConstraint.MaxInclusive(single())
            DynamicComponent.MIN_EXCLUSIVE -> PropertyConstraint.MinExclusive(single())
            DynamicComponent.MAX_EXCLUSIVE -> PropertyConstraint.MaxExclusive(single())
            DynamicComponent.UNIQUE_LANG -> PropertyConstraint.UniqueLang(isLexicallyTrue(single()))
            DynamicComponent.SINGLE_LINE -> PropertyConstraint.SingleLine(isLexicallyTrue(single()))
            DynamicComponent.MIN_LIST_LENGTH -> PropertyConstraint.MinListLength(parseNonNegativeInt(single(), "sh:minListLength"))
            DynamicComponent.MAX_LIST_LENGTH -> PropertyConstraint.MaxListLength(parseNonNegativeInt(single(), "sh:maxListLength"))
            DynamicComponent.UNIQUE_MEMBERS -> PropertyConstraint.UniqueMembers(isLexicallyTrue(single()))
        }
    }

    private val counts: Set<Iri> by lazy {
        setOf(SHACL.minCount, SHACL.maxCount, SHACL.minLength, SHACL.maxLength, SHACL.minListLength, SHACL.maxListLength)
    }
    private val bounds: Set<Iri> by lazy { setOf(SHACL.minInclusive, SHACL.maxInclusive, SHACL.minExclusive, SHACL.maxExclusive) }
    private val booleans: Set<Iri> by lazy { setOf(SHACL.uniqueLang, SHACL.singleLine, SHACL.uniqueMembers) }
    private val booleanLexicals = setOf("true", "false", "1", "0")

    /**
     * D6 type rule of [parameter] for one computed output; returns the reason it fails, or null. [outputs] is the number
     * of output nodes of the expression, for the parameters whose rule depends on it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun outputError(parameter: Iri, node: RdfTerm, outputs: Int): String? =
        when (parameter) {
            in counts -> try {
                parseNonNegativeInt(node, DynamicParameters.displayName(parameter))
                null
            } catch (e: ShapeCompileException) {
                e.message
            }
            in bounds -> if (node is Literal) null else "${DynamicParameters.displayName(parameter)} expects a literal, got $node"
            in booleans ->
                if (node is Literal && node.datatype == XSD.boolean && node.lexical in booleanLexicals) {
                    null
                } else {
                    "${DynamicParameters.displayName(parameter)} expects an xsd:boolean literal, got $node"
                }
            else -> "${DynamicParameters.displayName(parameter)} cannot be computed"
        }

    fun parseNonNegativeInt(term: RdfTerm, role: String): Int {
        val lit = term as? TypedLiteral ?: throw ShapeCompileException("$role expects xsd:integer, got $term")
        if (lit.datatype != XSD.integer && lit.datatype != XSD.nonNegativeInteger && lit.datatype != XSD.long) {
            throw ShapeCompileException("$role expects integer datatype, got ${lit.datatype}")
        }
        val v = lit.lexical.toIntOrNull() ?: throw ShapeCompileException("$role invalid lexical ${lit.lexical}")
        if (v < 0) throw ShapeCompileException("$role must be non-negative")
        return v
    }

    fun literalString(term: RdfTerm): String? =
        when (term) {
            is Literal -> term.lexical
            else -> null
        }
}
