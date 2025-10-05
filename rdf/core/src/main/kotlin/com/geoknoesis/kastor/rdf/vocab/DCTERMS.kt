package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * Dublin Core Terms vocabulary.
 * A vocabulary for describing digital and physical resources.
 * 
 * @see <a href="https://www.dublincore.org/specifications/dublin-core/dcmi-terms/">Dublin Core Terms</a>
 */
object DCTERMS : Vocabulary {
    override val namespace: String = "http://purl.org/dc/terms/"
    override val prefix: String = "dcterms"
    
    // Core classes
    val Agent: Iri by lazy { term("Agent") }
    val AgentClass: Iri by lazy { term("AgentClass") }
    val BibliographicResource: Iri by lazy { term("BibliographicResource") }
    val FileFormat: Iri by lazy { term("FileFormat") }
    val Frequency: Iri by lazy { term("Frequency") }
    val Jurisdiction: Iri by lazy { term("Jurisdiction") }
    val LicenseDocument: Iri by lazy { term("LicenseDocument") }
    val LinguisticSystem: Iri by lazy { term("LinguisticSystem") }
    val Location: Iri by lazy { term("Location") }
    val LocationPeriodOrJurisdiction: Iri by lazy { term("LocationPeriodOrJurisdiction") }
    val MediaType: Iri by lazy { term("MediaType") }
    val MediaTypeOrExtent: Iri by lazy { term("MediaTypeOrExtent") }
    val MethodOfAccrual: Iri by lazy { term("MethodOfAccrual") }
    val MethodOfInstruction: Iri by lazy { term("MethodOfInstruction") }
    val PeriodOfTime: Iri by lazy { term("PeriodOfTime") }
    val PhysicalMedium: Iri by lazy { term("PhysicalMedium") }
    val PhysicalResource: Iri by lazy { term("PhysicalResource") }
    val Policy: Iri by lazy { term("Policy") }
    val ProvenanceStatement: Iri by lazy { term("ProvenanceStatement") }
    val RightsStatement: Iri by lazy { term("RightsStatement") }
    val SizeOrDuration: Iri by lazy { term("SizeOrDuration") }
    val Standard: Iri by lazy { term("Standard") }
    
    // Core properties
    val abstract: Iri by lazy { term("abstract") }
    val accessRights: Iri by lazy { term("accessRights") }
    val accrualMethod: Iri by lazy { term("accrualMethod") }
    val accrualPeriodicity: Iri by lazy { term("accrualPeriodicity") }
    val accrualPolicy: Iri by lazy { term("accrualPolicy") }
    val alternative: Iri by lazy { term("alternative") }
    val audience: Iri by lazy { term("audience") }
    val available: Iri by lazy { term("available") }
    val bibliographicCitation: Iri by lazy { term("bibliographicCitation") }
    val conformsTo: Iri by lazy { term("conformsTo") }
    val contributor: Iri by lazy { term("contributor") }
    val coverage: Iri by lazy { term("coverage") }
    val created: Iri by lazy { term("created") }
    val creator: Iri by lazy { term("creator") }
    val date: Iri by lazy { term("date") }
    val dateAccepted: Iri by lazy { term("dateAccepted") }
    val dateCopyrighted: Iri by lazy { term("dateCopyrighted") }
    val dateSubmitted: Iri by lazy { term("dateSubmitted") }
    val description: Iri by lazy { term("description") }
    val educationLevel: Iri by lazy { term("educationLevel") }
    val extent: Iri by lazy { term("extent") }
    val format: Iri by lazy { term("format") }
    val hasFormat: Iri by lazy { term("hasFormat") }
    val hasPart: Iri by lazy { term("hasPart") }
    val hasVersion: Iri by lazy { term("hasVersion") }
    val identifier: Iri by lazy { term("identifier") }
    val instructionalMethod: Iri by lazy { term("instructionalMethod") }
    val isFormatOf: Iri by lazy { term("isFormatOf") }
    val isPartOf: Iri by lazy { term("isPartOf") }
    val isReferencedBy: Iri by lazy { term("isReferencedBy") }
    val isReplacedBy: Iri by lazy { term("isReplacedBy") }
    val isRequiredBy: Iri by lazy { term("isRequiredBy") }
    val issued: Iri by lazy { term("issued") }
    val isVersionOf: Iri by lazy { term("isVersionOf") }
    val language: Iri by lazy { term("language") }
    val license: Iri by lazy { term("license") }
    val mediator: Iri by lazy { term("mediator") }
    val medium: Iri by lazy { term("medium") }
    val modified: Iri by lazy { term("modified") }
    val provenance: Iri by lazy { term("provenance") }
    val publisher: Iri by lazy { term("publisher") }
    val references: Iri by lazy { term("references") }
    val relation: Iri by lazy { term("relation") }
    val replaces: Iri by lazy { term("replaces") }
    val requires: Iri by lazy { term("requires") }
    val rights: Iri by lazy { term("rights") }
    val rightsHolder: Iri by lazy { term("rightsHolder") }
    val source: Iri by lazy { term("source") }
    val spatial: Iri by lazy { term("spatial") }
    val subject: Iri by lazy { term("subject") }
    val tableOfContents: Iri by lazy { term("tableOfContents") }
    val temporal: Iri by lazy { term("temporal") }
    val title: Iri by lazy { term("title") }
    val type: Iri by lazy { term("type") }
    val valid: Iri by lazy { term("valid") }
}
