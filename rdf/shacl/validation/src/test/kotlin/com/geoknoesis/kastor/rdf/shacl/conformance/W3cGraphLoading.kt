package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.rdfTermFromJena
import java.nio.file.Files
import java.nio.file.Path
import org.apache.jena.rdf.model.RDFNode

/**
 * Converts a Jena node of a manifest's **expected** report to a Kastor term, keeping the literal lexical form written
 * in the manifest so expected and actual results compare on identical terms.
 */
internal fun lexicalPreservingTerm(node: RDFNode): RdfTerm {
    val converted = rdfTermFromJena(node)
    if (!node.isLiteral) return converted
    val lit = node.asLiteral()
    if (converted is Literal && converted.lexical == lit.lexicalForm) return converted
    return if (lit.language.isNullOrEmpty()) TypedLiteral(lit.lexicalForm, Iri(lit.datatypeURI)) else converted
}

/**
 * Loads a W3C test file through the **production parse path** (Kastor's Jena provider, the same code users run),
 * resolving relative IRIs against the file URI. Literal lexical forms are preserved by the provider, so the
 * conformance suite sees the test files exactly as written.
 */
internal fun loadW3cGraph(path: Path): RdfGraph =
    Files.newInputStream(path).use { JenaProvider().parseGraph(it, "TURTLE", path.toUri().toString()) }
