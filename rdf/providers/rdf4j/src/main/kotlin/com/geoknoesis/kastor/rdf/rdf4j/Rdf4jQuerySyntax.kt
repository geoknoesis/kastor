package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm

/*
 * Syntax-level helpers of [Rdf4jRepository]: the triples of a graph query result, and what an update request's syntax
 * tree says about its dataset.
 */

/**
 * The statements of a graph query result as RDF 1.2 triples (see [Rdf4jTerms.triplesOf]). A
 * `_:r rdf:reifies <<( s p o )>>` triple may come from a stored statement (an explicit one), be implied by a
 * statement about the quoted triple, or both, and by every statement about that quoted triple; it is returned
 * once. Only those triples are remembered for that, and how many is bounded:
 *
 * - with a [window], the most recently seen `window` of them are remembered (least recently seen first out), so a
 *   result of any size is streamed in bounded memory. A `rdf:reifies` triple whose statements are further apart in
 *   the result than `window` other reified triples is returned again (as SPARQL allows for `CONSTRUCT`, whose
 *   result a streaming consumer has to treat as a bag anyway);
 * - without one (`null`: results that are materialized as a whole anyway) every one is remembered and the
 *   de-duplication is exact.
 */
internal fun org.eclipse.rdf4j.query.GraphQueryResult.viewTriples(
    hashedReifiersRead: (String, org.eclipse.rdf4j.model.Triple) -> Unit,
    window: Int? = null,
): Sequence<RdfTriple> {
    val seen: (RdfTriple) -> Boolean = if (window == null) {
        val reifies = HashSet<RdfTriple>();
        { triple -> !reifies.add(triple) }
    } else {
        val recent = object : LinkedHashMap<RdfTriple, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RdfTriple, Unit>?): Boolean = size > window
        };
        // containsKey does not count as an access: get does.
        { triple -> (recent.get(triple) != null).also { known -> if (!known) recent[triple] = Unit } }
    }
    return iterator().asSequence()
        .flatMap { statement -> Rdf4jTerms.triplesOf(statement, null, hashedReifiersRead) }
        .filter { it.predicate != com.geoknoesis.kastor.rdf.vocab.RDF.reifies || it.obj !is TripleTerm || !seen(it) }
}

/**
 * For every operation of the update request [sparql], in order, whether it has a `USING` / `USING NAMED` clause
 * of its own. Read from the syntax tree of RDF4J's SPARQL parser (the one the update was prepared with), so
 * the word in a literal, an IRI or a comment, or a clause of another operation, is never taken for one.
 */
internal fun usingClauses(sparql: String): List<Boolean> =
    org.eclipse.rdf4j.query.parser.sparql.ast.SyntaxTreeBuilder.parseUpdateSequence(sparql).updateContainers
        .mapNotNull { it.update }
        .map { operation ->
            val with = (operation as? org.eclipse.rdf4j.query.parser.sparql.ast.ASTModify)?.withClause
            operation.datasetClauseList.any { it !== with }
        }

/** Whether the update [template] writes into a graph named by a variable (which may be a blank node). */
internal fun hasVariableContext(template: org.eclipse.rdf4j.query.algebra.TupleExpr): Boolean {
    var found = false
    template.visit(object : org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor<RuntimeException>() {
        override fun meet(node: org.eclipse.rdf4j.query.algebra.StatementPattern) {
            val context = node.contextVar
            if (context != null && !context.hasValue()) found = true
        }
    })
    return found
}

