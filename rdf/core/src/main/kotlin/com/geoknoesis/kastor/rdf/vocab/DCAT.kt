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
    
    // Core properties
    val datasetProp: Iri by lazy { term("dataset") }
    val distributionProp: Iri by lazy { term("distribution") }
    val downloadURL: Iri by lazy { term("downloadURL") }
    val mediaType: Iri by lazy { term("mediaType") }
}