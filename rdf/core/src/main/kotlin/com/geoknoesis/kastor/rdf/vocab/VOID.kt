package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * [Vocabulary of Interlinked Datasets (VoID)](https://www.w3.org/TR/void/).
 *
 * @see <a href="http://rdfs.org/ns/void#">VoID namespace</a>
 */
object VOID : Vocabulary {
    override val namespace: String = "http://rdfs.org/ns/void#"
    override val prefix: String = "void"

    val Dataset: Iri by lazy { term("Dataset") }
    val DatasetDescription: Iri by lazy { term("DatasetDescription") }
    val Linkset: Iri by lazy { term("Linkset") }
    val TechnicalFeature: Iri by lazy { term("TechnicalFeature") }

    val subset: Iri by lazy { term("subset") }
    val rootResource: Iri by lazy { term("rootResource") }
    val classes: Iri by lazy { term("classes") }
    val triples: Iri by lazy { term("triples") }
    val distinctSubjects: Iri by lazy { term("distinctSubjects") }
    val properties: Iri by lazy { term("properties") }
    val documents: Iri by lazy { term("documents") }
    val feature: Iri by lazy { term("feature") }
    val vocabulary: Iri by lazy { term("vocabulary") }
    val sparqlEndpoint: Iri by lazy { term("sparqlEndpoint") }
    val dataDump: Iri by lazy { term("dataDump") }
    val uriSpace: Iri by lazy { term("uriSpace") }
    val exampleResource: Iri by lazy { term("exampleResource") }
}
