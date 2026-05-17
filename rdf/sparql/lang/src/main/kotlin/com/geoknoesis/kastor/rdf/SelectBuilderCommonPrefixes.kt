@file:JvmName("SelectBuilderCommonPrefixes")

package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.sparql.SelectBuilder

/**
 * Extension to add common vocabulary prefixes to a [SelectBuilder].
 *
 * Lives in `:rdf:sparql-lang` next to [SelectBuilder].
 */
fun SelectBuilder.addCommonPrefixes(vararg prefixes: String) {
    prefixes.forEach { prefix ->
        when (prefix.lowercase()) {
            "foaf" -> this.prefix("foaf", CommonPrefixes.FOAF)
            "rdf" -> this.prefix("rdf", CommonPrefixes.RDF)
            "rdfs" -> this.prefix("rdfs", CommonPrefixes.RDFS)
            "owl" -> this.prefix("owl", CommonPrefixes.OWL)
            "xsd" -> this.prefix("xsd", CommonPrefixes.XSD)
            "dc" -> this.prefix("dc", CommonPrefixes.DC)
            "dcterms" -> this.prefix("dcterms", CommonPrefixes.DCTERMS)
            "schema" -> this.prefix("schema", CommonPrefixes.SCHEMA)
            "dbpedia" -> this.prefix("dbpedia", CommonPrefixes.DBPEDIA)
            "wikidata" -> this.prefix("wikidata", CommonPrefixes.WIKIDATA)
            "skos" -> this.prefix("skos", CommonPrefixes.SKOS)
            "prov" -> this.prefix("prov", CommonPrefixes.PROV)
            "dcat" -> this.prefix("dcat", CommonPrefixes.DCAT)
            "void" -> this.prefix("void", CommonPrefixes.VOID)
            "geo" -> this.prefix("geo", CommonPrefixes.GEO)
            "time" -> this.prefix("time", CommonPrefixes.TIME)
            else -> throw IllegalArgumentException("Unknown common prefix: $prefix")
        }
    }
}
