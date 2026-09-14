@file:JvmName("Rdf4jInterop")

package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/*
 * Public conversions between Kastor terms and RDF4J values, backed by the provider's own converter so
 * every RDF4J integration (reasoning, SHACL, applications) shares one mapping: exact lexical forms,
 * RDF 1.2 triple terms and base direction (stored in RDF4J's `lang--dir` tag form).
 */

/** Converts an RDF4J [Value] to a Kastor [RdfTerm]. */
fun rdfTermFromRdf4j(value: Value): RdfTerm = Rdf4jTerms.fromRdf4jValue(value)

/** Converts a Kastor [RdfTerm] to an RDF4J [Value]. */
fun rdf4jValueOf(term: RdfTerm): Value = Rdf4jTerms.toRdf4jValue(term)

/** Converts a Kastor [RdfResource] (IRI or blank node) to an RDF4J [Resource]. */
fun rdf4jResourceOf(resource: RdfResource): Resource = Rdf4jTerms.toRdf4jResource(resource)

/**
 * Converts an RDF4J [Statement] (its context is ignored) to a Kastor [RdfTriple].
 *
 * An RDF-star quoted-triple subject (not representable in RDF 1.2) becomes its deterministic reifier blank
 * node; the matching `_:r rdf:reifies <<( s p o )>>` triple is not part of this single-triple result.
 */
fun rdfTripleFromRdf4j(statement: Statement): RdfTriple = RdfTriple(
    Rdf4jTerms.fromRdf4jResource(statement.subject),
    Rdf4jTerms.fromRdf4jIri(statement.predicate),
    Rdf4jTerms.fromRdf4jValue(statement.`object`),
)

/** Converts a Kastor [RdfTriple] to a context-less RDF4J [Statement]. */
fun rdf4jStatementOf(triple: RdfTriple): Statement = SimpleValueFactory.getInstance().createStatement(
    Rdf4jTerms.toRdf4jResource(triple.subject),
    Rdf4jTerms.toRdf4jIri(triple.predicate),
    Rdf4jTerms.toRdf4jValue(triple.obj),
)
