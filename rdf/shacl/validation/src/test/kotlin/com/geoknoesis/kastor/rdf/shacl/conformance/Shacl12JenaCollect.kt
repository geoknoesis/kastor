package com.geoknoesis.kastor.rdf.shacl.conformance

import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Statement
import org.apache.jena.rdf.model.StmtIterator

internal fun StmtIterator.collectStatements(): List<Statement> {
    val out = mutableListOf<Statement>()
    while (hasNext()) {
        out.add(next())
    }
    return out
}

internal fun org.apache.jena.util.iterator.ExtendedIterator<Resource>.collectResources(): List<Resource> {
    val out = mutableListOf<Resource>()
    while (hasNext()) {
        out.add(next())
    }
    return out
}
