package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.SHACL

/** `sh:` terms of SHACL 1.2 SPARQL that the shared vocabulary in rdf/core does not define. */
internal object ShaclSparqlTerms {
    private fun term(local: String) = Iri(SHACL.namespace + local)

    val validator = term("validator")
    val nodeValidator = term("nodeValidator")
    val propertyValidator = term("propertyValidator")
    val SPARQLAskValidator = term("SPARQLAskValidator")
    val SPARQLSelectValidator = term("SPARQLSelectValidator")
    val optional = term("optional")
    val resultAnnotation = term("resultAnnotation")
    val annotationProperty = term("annotationProperty")
    val annotationVarName = term("annotationVarName")
    val annotationValue = term("annotationValue")
    val values = term("values")
    val defaultValue = term("defaultValue")
    val sparqlExpr = term("sparqlExpr")
    val ShapesGraph = term("ShapesGraph")
    val DataGraph = term("DataGraph")
    val RulesGraph = term("RulesGraph")
}
