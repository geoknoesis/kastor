package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.rdfTermFromJena
import java.nio.file.Path
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.riot.RDFDataMgr

/**
 * Converts a Jena node to a Kastor term **preserving literal lexical forms**.
 *
 * The Jena bridge (`JenaTerms.fromNode`) re-renders some typed literals through `java.time` / Kotlin numbers
 * (e.g. `"2002-10-10T12:00:00"^^xsd:dateTime` becomes `"2002-10-10T12:00"`), which changes their lexical form and
 * therefore SHACL `sh:datatype` / comparison outcomes. W3C conformance must see the test files verbatim.
 */
internal fun lexicalPreservingTerm(node: RDFNode): RdfTerm {
    val converted = rdfTermFromJena(node)
    if (!node.isLiteral) return converted
    val lit = node.asLiteral()
    if (converted is Literal && converted.lexical == lit.lexicalForm) return converted
    return if (lit.language.isNullOrEmpty()) TypedLiteral(lit.lexicalForm, Iri(lit.datatypeURI)) else converted
}

/** Loads a Turtle test file into a Kastor graph without altering literal lexical forms. */
internal fun loadW3cGraph(path: Path): RdfGraph {
    val model = ModelFactory.createDefaultModel()
    try {
        RDFDataMgr.read(model, path.toUri().toString())
        return Rdf.graph {
            for (st in model.listStatements().collectStatements()) {
                triple(lexicalPreservingTerm(st.subject) as RdfResource, Iri(st.predicate.uri), lexicalPreservingTerm(st.`object`))
            }
        }
    } finally {
        model.close()
    }
}
