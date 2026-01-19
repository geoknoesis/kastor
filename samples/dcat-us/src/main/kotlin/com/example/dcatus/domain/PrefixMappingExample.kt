package com.example.dcatus.domain

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.PrefixMapping
import com.geoknoesis.kastor.gen.annotations.RdfClass
import com.geoknoesis.kastor.gen.annotations.RdfProperty

/**
 * Example demonstrating the use of prefix mappings and QNames in Kastor Gen annotations.
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
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface CatalogWithPrefixes {
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/publisher")
    val publisher: AgentWithPrefixes
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<DatasetWithPrefixes>
    
    @get:RdfProperty(iri = "http://www.w3.org/2004/02/skos/core#altLabel")
    val alternativeLabels: List<String>
}

@RdfClass(iri = "http://www.w3.org/ns/dcat#Dataset")
interface DatasetWithPrefixes {
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#distribution")
    val distribution: List<DistributionWithPrefixes>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/keyword")
    val keywords: List<String>
}

@RdfClass(iri = "http://www.w3.org/ns/dcat#Distribution")
interface DistributionWithPrefixes {
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#downloadURL")
    val downloadUrl: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#mediaType")
    val mediaType: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/format")
    val format: String
}

@RdfClass(iri = "http://xmlns.com/foaf/0.1/Agent")
interface AgentWithPrefixes {
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/homepage")
    val homepage: String
}

/**
 * Example showing mixed usage of QNames and full IRIs.
 * You can mix QNames (with prefix mappings) and full IRIs in the same interface.
 */
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog") // Full IRI
interface MixedUsageExample {
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title") // QName
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description") // Full IRI
    val description: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset") // QName
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
// Temporarily disabled to fix build issues
// @RdfClass(iri = "http://example.org/Location")
interface LocationWithCustomPrefixes {
    
    @get:RdfProperty(iri = "http://example.org/name")
    val name: String
    
    @get:RdfProperty(iri = "http://schema.org/address")
    val address: String
    
    @get:RdfProperty(iri = "http://www.w3.org/2003/01/geo/wgs84_pos#lat")
    val latitude: Double
    
    @get:RdfProperty(iri = "http://www.w3.org/2003/01/geo/wgs84_pos#long")
    val longitude: Double
}

/**
 * Example showing file-level prefix mappings.
 * When declared at the file level, all classes in the file can use the prefixes.
 */
// Temporarily disabled - file-level annotations not properly supported yet
// @file:PrefixMapping(
//     prefixes = [
//         Prefix("vcard", "http://www.w3.org/2006/vcard/ns#"),
//         Prefix("org", "http://www.w3.org/ns/org#")
//     ]
// )

// Temporarily disabled to fix build issues
// @RdfClass(iri = "http://www.w3.org/2006/vcard/ns#Individual")
interface PersonWithFilePrefixes {
    
    @get:RdfProperty(iri = "http://www.w3.org/2006/vcard/ns#fn") // Full name
    val fullName: String
    
    @get:RdfProperty(iri = "http://www.w3.org/2006/vcard/ns#email")
    val email: String
    
    @get:RdfProperty(iri = "http://www.w3.org/2006/vcard/ns#tel")
    val telephone: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/org#memberOf")
    val organization: String
}

// Temporarily disabled to fix build issues
// @RdfClass(iri = "http://www.w3.org/ns/org#Organization")
interface OrganizationWithFilePrefixes {
    
    @get:RdfProperty(iri = "http://www.w3.org/2006/vcard/ns#fn")
    val name: String
    
    @get:RdfProperty(iri = "http://www.w3.org/2006/vcard/ns#email")
    val email: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/org#hasMember")
    val members: List<PersonWithFilePrefixes>
}









