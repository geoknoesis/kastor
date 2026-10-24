package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.SHACL

/** `sh:` terms of SHACL 1.2 SPARQL and Node Expressions that the shared vocabulary in rdf/core does not define. */
internal object ShaclSparqlTerms {
    /** SHACL 1.2 node expression vocabulary namespace (`shnex:`). */
    const val SHNEX_NAMESPACE = "http://www.w3.org/ns/shacl-node-expr#"

    /** SPARQL function namespace of SHACL 1.2 SPARQL function expressions (`sparql:concat ( … )`, Node Expressions §5). */
    const val SPARQL_NAMESPACE = "http://www.w3.org/ns/sparql#"

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
    val ListParameterExpressionFunction = term("ListParameterExpressionFunction")
    val NamedParameterExpressionFunction = term("NamedParameterExpressionFunction")
    val keyParameter = term("keyParameter")
    val bodyExpression = term("bodyExpression")
    val ShapesGraph = term("ShapesGraph")
    val DataGraph = term("DataGraph")
    val RulesGraph = term("RulesGraph")
}
