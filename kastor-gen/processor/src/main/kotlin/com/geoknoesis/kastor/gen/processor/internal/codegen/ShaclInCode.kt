package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode

/**
 * Generated `sh:in` membership tests. SHACL compares value nodes with the members as RDF terms, so a member matches
 * only a value of the same term kind: an IRI the same IRI, a language-tagged literal the same lexical form and
 * language tag (ignoring case, as RDF does), any other literal the same lexical form and datatype.
 */
internal object ShaclInCode {
    private const val RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
    private val literalClass = ClassName(CodegenConstants.RDF_PACKAGE, "Literal")
    private val langStringClass = ClassName(CodegenConstants.RDF_PACKAGE, "LangString")
    private val iriClass = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")

    /**
     * The members to compare with: the typed members when the model has them, otherwise the plain [values] read as
     * IRIs ([iriValued]) or as literals without a type of their own; null when there is no `sh:in`.
     */
    fun members(typed: List<ShaclInValue>?, values: List<String>?, iriValued: Boolean): List<ShaclInValue>? =
        typed?.takeIf { it.isNotEmpty() }
            ?: values?.takeIf { it.isNotEmpty() }?.map { ShaclInValue(value = it, isIri = iriValued) }

    /**
     * Boolean expression that is true when the RDF term expression [value] is one of [members].
     *
     * @param propertyDatatype the property's `sh:datatype`: the datatype of literal members that carry none. A
     *   literal member without any datatype information is compared by lexical form only.
     */
    fun isMember(value: String, members: List<ShaclInValue>, propertyDatatype: String?): CodeBlock =
        members.map { matches(value, it, propertyDatatype) }.joinToCode(" || ")

    private fun matches(value: String, member: ShaclInValue, propertyDatatype: String?): CodeBlock {
        if (member.isIri) return CodeBlock.of("%L == %T(%S)", value, iriClass, member.value)
        if (member.language != null) {
            return CodeBlock.of("%L == %T(%S, %S)", value, langStringClass, member.value, member.language)
        }
        val datatype = (member.datatype ?: propertyDatatype)?.takeIf { it != RDF_LANG_STRING }
        return if (datatype != null) {
            // A language-tagged literal has the datatype rdf:langString, so it never equals a typed member.
            CodeBlock.of("(%L is %T && %L.lexical == %S && %L.datatype.value == %S)", value, literalClass, value, member.value, value, datatype)
        } else {
            CodeBlock.of("(%L is %T && %L.lexical == %S)", value, literalClass, value, member.value)
        }
    }
}
