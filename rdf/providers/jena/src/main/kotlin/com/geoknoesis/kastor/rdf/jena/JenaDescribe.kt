package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.RdfTriple
import org.apache.jena.graph.Node
import org.apache.jena.query.Dataset
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.rdf.model.ModelFactory

/**
 * Describes the resources of a `DESCRIBE` query **from the default graph of the query's dataset**: the store's
 * default graph or, when the query has `FROM` / `FROM NAMED` clauses, the default graph they declare (the merge of
 * the `FROM` graphs; empty with only `FROM NAMED`). This is the contract of every provider (see the RDF4J
 * provider and `Dataset.describe`). Named graphs are read by the `WHERE` clause inside `GRAPH`, never by the
 * description itself.
 *
 * Jena's own `execDescribe` is not used: its describe handler reads the store's default graph **and every named
 * graph of the store**, whatever the query's dataset. The query is evaluated as Jena does (the `WHERE` clause with
 * its solution modifiers yields the values of the described variables, the IRIs of the `DESCRIBE` clause are added)
 * and each resource is described as Jena's handler does (its statements and the closure over blank nodes), but
 * in that one graph. On an inference repository that graph is the inference view, so entailed triples are included.
 */
internal object JenaDescribe {

    /** The description of the `DESCRIBE` query [sparql] over [dataset], read inside the caller's read transaction. */
    fun describe(sparql: String, dataset: Dataset): Sequence<RdfTriple> {
        val parsed = QueryFactory.create(JenaLiteralBounds.requireBoundedQuery(sparql))
        if (!parsed.isDescribeType) throw IllegalArgumentException("Not a DESCRIBE query")
        val resources = LinkedHashSet<Node>()
        if (parsed.queryPattern != null) {
            // The same query as a SELECT of the described variables (DESCRIBE * selects every variable).
            val select = QueryFactory.create(JenaLiteralBounds.requireBoundedQuery(sparql)).apply { setQuerySelectType() }
            QueryExecutionFactory.create(select, dataset).use { exec ->
                val rows = exec.execSelect()
                val names = rows.resultVars
                rows.forEachRemaining { row -> names.forEach { name -> row.get(name)?.asNode()?.let(resources::add) } }
            }
        }
        parsed.resultURIs?.let(resources::addAll)
        val source = ModelFactory.createModelForGraph(
            if (parsed.hasDatasetDescription()) {
                org.apache.jena.sparql.core.DynamicDatasets
                    .dynamicDataset(parsed.datasetDescription, dataset.asDatasetGraph(), false).defaultGraph
            } else {
                dataset.asDatasetGraph().defaultGraph
            },
        )
        val described = ModelFactory.createDefaultModel()
        return try {
            for (node in resources) {
                if (!node.isURI && !node.isBlank) continue
                org.apache.jena.sparql.util.Closure.closure(source.asRDFNode(node).asResource(), false, described)
            }
            JenaGraph(described).getTriples().asSequence()
        } finally {
            described.close()
        }
    }
}
