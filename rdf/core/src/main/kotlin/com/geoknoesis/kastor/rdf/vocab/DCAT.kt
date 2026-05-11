package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * DCAT (Data Catalog Vocabulary) vocabulary.
 * A vocabulary for describing data catalogs and datasets.
 * 
 * @see <a href="https://www.w3.org/TR/vocab-dcat/">DCAT Specification</a>
 */
object DCAT : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/dcat#"
    override val prefix: String = "dcat"
    
    // Core classes
    val Catalog: Iri by lazy { term("Catalog") }
    val Dataset: Iri by lazy { term("Dataset") }
    val Distribution: Iri by lazy { term("Distribution") }
    val DataService: Iri by lazy { term("DataService") }
    val CatalogRecord: Iri by lazy { term("CatalogRecord") }
    val Relationship: Iri by lazy { term("Relationship") }
    val Role: Iri by lazy { term("Role") }

    // Core properties
    val datasetProp: Iri by lazy { term("dataset") }
    val distributionProp: Iri by lazy { term("distribution") }
    val catalogProp: Iri by lazy { term("catalog") }
    val record: Iri by lazy { term("record") }
    val service: Iri by lazy { term("service") }
    val accessURL: Iri by lazy { term("accessURL") }
    val downloadURL: Iri by lazy { term("downloadURL") }
    val mediaType: Iri by lazy { term("mediaType") }
    val byteSize: Iri by lazy { term("byteSize") }
    val keyword: Iri by lazy { term("keyword") }
    val landingPage: Iri by lazy { term("landingPage") }
    val theme: Iri by lazy { term("theme") }
    val servesDataset: Iri by lazy { term("servesDataset") }
    val endpointURL: Iri by lazy { term("endpointURL") }
    val endpointDescription: Iri by lazy { term("endpointDescription") }
}









