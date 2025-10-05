package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * SHACL (Shapes Constraint Language) vocabulary.
 * A vocabulary for defining data validation constraints.
 * 
 * @see <a href="https://www.w3.org/TR/shacl/">SHACL Specification</a>
 */
object SHACL : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/shacl#"
    override val prefix: String = "sh"
    
    // Core classes
    val NodeShape: Iri by lazy { term("NodeShape") }
    val PropertyShape: Iri by lazy { term("PropertyShape") }
    val Shape: Iri by lazy { term("Shape") }
    val ValidationReport: Iri by lazy { term("ValidationReport") }
    val ValidationResult: Iri by lazy { term("ValidationResult") }
    val ConstraintComponent: Iri by lazy { term("ConstraintComponent") }
    val Function: Iri by lazy { term("Function") }
    val Parameter: Iri by lazy { term("Parameter") }
    val SPARQLConstraint: Iri by lazy { term("SPARQLConstraint") }
    val SPARQLConstraintComponent: Iri by lazy { term("SPARQLConstraintComponent") }
    val SPARQLFunction: Iri by lazy { term("SPARQLFunction") }
    val SPARQLTarget: Iri by lazy { term("SPARQLTarget") }
    val SPARQLTargetType: Iri by lazy { term("SPARQLTargetType") }
    val Target: Iri by lazy { term("Target") }
    val TargetType: Iri by lazy { term("TargetType") }
    
    // Core properties
    val targetClass: Iri by lazy { term("targetClass") }
    val targetNode: Iri by lazy { term("targetNode") }
    val targetObjectsOf: Iri by lazy { term("targetObjectsOf") }
    val targetSubjectsOf: Iri by lazy { term("targetSubjectsOf") }
    val deactivated: Iri by lazy { term("deactivated") }
    val closed: Iri by lazy { term("closed") }
    val ignoredProperties: Iri by lazy { term("ignoredProperties") }
    val property: Iri by lazy { term("property") }
    val node: Iri by lazy { term("node") }
    val not: Iri by lazy { term("not") }
    val and: Iri by lazy { term("and") }
    val or: Iri by lazy { term("or") }
    val xone: Iri by lazy { term("xone") }
    val minCount: Iri by lazy { term("minCount") }
    val maxCount: Iri by lazy { term("maxCount") }
    val uniqueLang: Iri by lazy { term("uniqueLang") }
    val languageIn: Iri by lazy { term("languageIn") }
    val equals: Iri by lazy { term("equals") }
    val disjoint: Iri by lazy { term("disjoint") }
    val lessThan: Iri by lazy { term("lessThan") }
    val lessThanOrEquals: Iri by lazy { term("lessThanOrEquals") }
    val pattern: Iri by lazy { term("pattern") }
    val flags: Iri by lazy { term("flags") }
    val hasValue: Iri by lazy { term("hasValue") }
    val `in`: Iri by lazy { term("in") }
    val hasShape: Iri by lazy { term("hasShape") }
    val qualifiedValueShape: Iri by lazy { term("qualifiedValueShape") }
    val qualifiedMinCount: Iri by lazy { term("qualifiedMinCount") }
    val qualifiedMaxCount: Iri by lazy { term("qualifiedMaxCount") }
    val qualifiedValueShapesDisjoint: Iri by lazy { term("qualifiedValueShapesDisjoint") }
    val severity: Iri by lazy { term("severity") }
    val message: Iri by lazy { term("message") }
    val resultPath: Iri by lazy { term("resultPath") }
    val resultSeverity: Iri by lazy { term("resultSeverity") }
    val resultMessage: Iri by lazy { term("resultMessage") }
    val resultFocusNode: Iri by lazy { term("resultFocusNode") }
    val resultValue: Iri by lazy { term("resultValue") }
    val conforms: Iri by lazy { term("conforms") }
    val detail: Iri by lazy { term("detail") }
    val focusNode: Iri by lazy { term("focusNode") }
    val sourceConstraint: Iri by lazy { term("sourceConstraint") }
    val sourceConstraintComponent: Iri by lazy { term("sourceConstraintComponent") }
    val sourceShape: Iri by lazy { term("sourceShape") }
    val value: Iri by lazy { term("value") }
}
