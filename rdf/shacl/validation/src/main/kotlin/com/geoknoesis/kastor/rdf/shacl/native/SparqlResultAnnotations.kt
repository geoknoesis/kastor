package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * A `sh:resultAnnotation` (SHACL 1.2 SPARQL 5): each result gets [property] with the solution's binding of
 * [variable], or with [defaults] (`sh:annotationValue`) when the variable is unbound.
 */
internal data class SparqlResultAnnotation(val property: Iri, val variable: String, val defaults: List<RdfTerm>)

/** Reads the `sh:resultAnnotation` values of [node] (a `sh:sparql` node or a SPARQL-based constraint component). */
internal fun compileResultAnnotations(node: RdfResource, index: ShapeGraphIndex): List<SparqlResultAnnotation> =
    index.objects(node, ShaclSparqlTerms.resultAnnotation).map { annotation ->
        if (annotation !is RdfResource) throw ShapeCompileException("sh:resultAnnotation of $node must be an IRI or blank node")
        val property = index.objects(annotation, ShaclSparqlTerms.annotationProperty).singleOrNull() as? Iri
            ?: throw ShapeCompileException("sh:resultAnnotation $annotation must have exactly one sh:annotationProperty IRI")
        val names = index.objects(annotation, ShaclSparqlTerms.annotationVarName)
        if (names.size > 1) throw ShapeCompileException("sh:resultAnnotation $annotation has more than one sh:annotationVarName")
        val variable = names.singleOrNull()?.let { name ->
            if (name !is Literal || name is LangString || name.datatype != XSD.string) {
                throw ShapeCompileException("sh:annotationVarName of $annotation must be an xsd:string literal")
            }
            name.lexical
        } ?: SparqlQueryTemplate.localName(property)
        SparqlResultAnnotation(property, variable, index.objects(annotation, ShaclSparqlTerms.annotationValue))
    }
