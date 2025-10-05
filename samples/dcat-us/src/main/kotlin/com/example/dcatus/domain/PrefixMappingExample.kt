package com.example.dcatus.domain

import com.example.ontomapper.annotations.Prefix
import com.example.ontomapper.annotations.PrefixMapping
import com.example.ontomapper.annotations.RdfClass
import com.example.ontomapper.annotations.RdfProperty

/**
 * Example demonstrating the use of prefix mappings and QNames in OntoMapper annotations.
 * 
 * This shows how to declare prefix mappings and use QNames instead of full IRIs
 * in RdfClass and RdfProperty annotations.
 */

// Declare prefix mappings for common vocabularies
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        Prefix("foaf", "http://xmlns.com/foaf/0.1/"),
        Prefix("skos", "http://www.w3.org/2004/02/skos/core#"),
        Prefix("xsd", "http://www.w3.org/2001/XMLSchema#")
    ]
)
@RdfClass(iri = "dcat:Catalog")
interface CatalogWithPrefixes {
    
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcterms:description")
    val description: String
    
    @get:RdfProperty(iri = "dcterms:publisher")
    val publisher: AgentWithPrefixes
    
    @get:RdfProperty(iri = "dcat:dataset")
    val dataset: List<DatasetWithPrefixes>
    
    @get:RdfProperty(iri = "skos:altLabel")
    val alternativeLabels: List<String>
}

@RdfClass(iri = "dcat:Dataset")
interface DatasetWithPrefixes {
    
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcterms:description")
    val description: String
    
    @get:RdfProperty(iri = "dcat:distribution")
    val distribution: List<DistributionWithPrefixes>
    
    @get:RdfProperty(iri = "dcterms:keyword")
    val keywords: List<String>
}

@RdfClass(iri = "dcat:Distribution")
interface DistributionWithPrefixes {
    
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcat:downloadURL")
    val downloadUrl: String
    
    @get:RdfProperty(iri = "dcat:mediaType")
    val mediaType: String
    
    @get:RdfProperty(iri = "dcterms:format")
    val format: String
}

@RdfClass(iri = "foaf:Agent")
interface AgentWithPrefixes {
    
    @get:RdfProperty(iri = "foaf:name")
    val name: String
    
    @get:RdfProperty(iri = "foaf:homepage")
    val homepage: String
}

/**
 * Example showing mixed usage of QNames and full IRIs.
 * You can mix QNames (with prefix mappings) and full IRIs in the same interface.
 */
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog") // Full IRI
interface MixedUsageExample {
    
    @get:RdfProperty(iri = "dcterms:title") // QName
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description") // Full IRI
    val description: String
    
    @get:RdfProperty(iri = "dcat:dataset") // QName
    val dataset: List<DatasetWithPrefixes>
    
    @get:RdfProperty(iri = "http://www.w3.org/2004/02/skos/core#altLabel") // Full IRI
    val alternativeLabels: List<String>
}

/**
 * Example showing how to use custom prefixes for your own vocabulary.
 */
@PrefixMapping(
    prefixes = [
        Prefix("ex", "http://example.org/vocab#"),
        Prefix("schema", "http://schema.org/"),
        Prefix("geo", "http://www.w3.org/2003/01/geo/wgs84_pos#")
    ]
)
@RdfClass(iri = "ex:Location")
interface LocationWithCustomPrefixes {
    
    @get:RdfProperty(iri = "ex:name")
    val name: String
    
    @get:RdfProperty(iri = "schema:address")
    val address: String
    
    @get:RdfProperty(iri = "geo:lat")
    val latitude: Double
    
    @get:RdfProperty(iri = "geo:long")
    val longitude: Double
}

/**
 * Example showing file-level prefix mappings.
 * When declared at the file level, all classes in the file can use the prefixes.
 */
@file:PrefixMapping(
    prefixes = [
        Prefix("vcard", "http://www.w3.org/2006/vcard/ns#"),
        Prefix("org", "http://www.w3.org/ns/org#")
    ]
)

@RdfClass(iri = "vcard:Individual")
interface PersonWithFilePrefixes {
    
    @get:RdfProperty(iri = "vcard:fn") // Full name
    val fullName: String
    
    @get:RdfProperty(iri = "vcard:email")
    val email: String
    
    @get:RdfProperty(iri = "vcard:tel")
    val telephone: String
    
    @get:RdfProperty(iri = "org:memberOf")
    val organization: String
}

@RdfClass(iri = "org:Organization")
interface OrganizationWithFilePrefixes {
    
    @get:RdfProperty(iri = "vcard:fn")
    val name: String
    
    @get:RdfProperty(iri = "vcard:email")
    val email: String
    
    @get:RdfProperty(iri = "org:hasMember")
    val members: List<PersonWithFilePrefixes>
}
