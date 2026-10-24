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

    /**
     * Builds the constant constraint; throws ShapeCompileException on an ill-typed value. Null = no constraint. For
     * computed values, `resolveDynamic` wraps that error in a "Dynamic SHACL: …" ShaclValidationException.
     *
     * A list-taking parameter ([ParameterShape.IRI_OR_LIST], [ParameterShape.LIST]) takes its values as the members:
     * the IRI of a constant `sh:class ex:C`, the parsed members of a constant list, or the outputs of an expression.
     * No member makes no constraint for `sh:class`, `sh:datatype` and `sh:nodeKind`, and the constraint of an empty
     * list for the others, as constant empty lists always did; computed `IRI_OR_LIST` values never get here empty (D6).
     */
    fun instantiate(component: DynamicComponent, values: Map<Iri, List<RdfTerm>>, extras: ComponentExtras, config: ValidationConfig): PropertyConstraint? {
        fun single(): RdfTerm = values[component.parameters.single()]?.singleOrNull()
            ?: throw ShapeCompileException("${DynamicParameters.displayName(component.parameters.single())} needs exactly one value")
        fun members(): List<RdfTerm> = values[component.parameters.single()].orEmpty()
        fun iris(notIri: (RdfTerm) -> String): List<Iri> = members().map { it as? Iri ?: throw ShapeCompileException(notIri(it)) }
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
            DynamicComponent.CLASS -> iris { "sh:class list entries must be IRIs: $it" }.let {
                when (it.size) {
                    0 -> null
                    1 -> PropertyConstraint.Class(it.single())
                    else -> PropertyConstraint.ClassAnyOf(it)
                }
            }
            DynamicComponent.DATATYPE -> iris { "sh:datatype list entries must be IRIs: $it" }.takeIf { it.isNotEmpty() }
                ?.let { PropertyConstraint.Datatype(it, extras.severityOverride, extras.messages) }
            DynamicComponent.NODE_KIND -> iris { "sh:nodeKind list entries must be IRIs: $it" }.takeIf { it.isNotEmpty() }?.let { PropertyConstraint.NodeKind(it.distinct()) }
            DynamicComponent.ROOT_CLASS -> PropertyConstraint.RootClass(iris { "sh:rootClass list members must be IRIs" })
            DynamicComponent.IN -> members().let { terms ->
                terms.forEach { ShapesCompiler.ensureShapeTermAllowed(it, config) }
                PropertyConstraint.In(terms)
            }
            DynamicComponent.LANGUAGE_IN -> PropertyConstraint.LanguageIn(
                members().map { literalString(it) ?: throw ShapeCompileException("sh:languageIn list must be string literals") },
            )
        }
    }

    private val counts: Set<Iri> by lazy {
        setOf(SHACL.minCount, SHACL.maxCount, SHACL.minLength, SHACL.maxLength, SHACL.minListLength, SHACL.maxListLength)
    }
    private val bounds: Set<Iri> by lazy { setOf(SHACL.minInclusive, SHACL.maxInclusive, SHACL.minExclusive, SHACL.maxExclusive) }
    private val booleans: Set<Iri> by lazy { setOf(SHACL.uniqueLang, SHACL.singleLine, SHACL.uniqueMembers) }
    private val booleanLexicals = setOf("true", "false", "1", "0")
    private val iriMembers: Set<Iri> by lazy { setOf(SHACL.`class`, SHACL.datatype, SHACL.rootClass) }

    /** Core 1.2 §7.1.3: the node kinds of an IRI value, and those of the members of a list value. */
    private val nodeKinds: Set<Iri> by lazy {
        setOf(SHACL.BlankNode, SHACL.IRI, SHACL.Literal, SHACL.BlankNodeOrIRI, SHACL.BlankNodeOrLiteral, SHACL.IRIOrLiteral, SHACL.TripleTerm)
    }
    private val listNodeKinds: Set<Iri> by lazy { setOf(SHACL.BlankNode, SHACL.IRI, SHACL.Literal, SHACL.TripleTerm) }

    /**
     * D6 type rule of [parameter] for one computed output; returns the reason it fails, or null. [outputs] is the number
     * of output nodes of the expression, for the parameters whose rule depends on it (`sh:nodeKind`).
     */
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
            in iriMembers -> if (node is Iri) null else "${DynamicParameters.displayName(parameter)} expects IRIs, got $node"
            SHACL.nodeKind -> when {
                outputs == 1 && node in nodeKinds -> null
                outputs == 1 -> "sh:nodeKind expects one of ${nodeKinds.joinToString { DynamicParameters.displayName(it) }}, got $node"
                node in listNodeKinds -> null
                else -> "sh:nodeKind list members must be one of ${listNodeKinds.joinToString { DynamicParameters.displayName(it) }}, got $node"
            }
            SHACL.languageIn ->
                if (node is Literal && node.datatype == XSD.string) null else "sh:languageIn expects string literals, got $node"
            // Any node may be a member; triple terms are checked by instantiate (allowTripleTermsInShapeParameters).
            SHACL.`in` -> null
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
