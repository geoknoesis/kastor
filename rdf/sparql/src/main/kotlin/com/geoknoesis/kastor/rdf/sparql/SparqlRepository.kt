package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.FalseLiteral
import java.net.URL
import java.net.HttpURLConnection
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.io.InputStreamReader

class SparqlRepository(private val endpoint: String) : RdfRepository {
    
    override val defaultGraph: RdfGraph = SparqlGraph(this)
    
    override fun getGraph(name: Iri): RdfGraph = SparqlGraph(this, name)
    
    override fun hasGraph(name: Iri): Boolean {
        val query = SparqlAskQuery("ASK { GRAPH <${name.value}> { ?s ?p ?o } }")
        return ask(query)
    }
    
    override fun listGraphs(): List<Iri> {
        val query = SparqlSelectQuery("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")
        val result = select(query)
        return result.mapNotNull { binding -> 
            binding.get("g")?.let { term -> 
                if (term is Iri) term else null 
            }
        }
    }
    
    override fun createGraph(name: Iri): RdfGraph = SparqlGraph(this, name)
    
    override fun removeGraph(name: Iri): Boolean {
        val update = UpdateQuery("DROP GRAPH <${name.value}>")
        update(update)
        return true
    }

    override fun editDefaultGraph(): GraphEditor {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): GraphEditor {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult {
        val response = executeQuery(query.sparql)
        return SparqlResultSet(response)
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        val response = executeQuery(query.sparql)
        return response.trim().equals("true", ignoreCase = true)
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        executeQuery(query.sparql)
        // Simple parsing - in practice would use proper RDF parser
        return emptySequence() // Placeholder
    }
    
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        executeQuery(query.sparql)
        // Simple parsing - in practice would use proper RDF parser
        return emptySequence() // Placeholder
    }
    
    override fun update(query: UpdateQuery) {
        executeUpdate(query.sparql)
    }
    
    override fun transaction(operations: RdfRepository.() -> Unit) {
        // SPARQL endpoints typically don't support transactions
        operations()
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        // SPARQL endpoints typically don't support transactions
        operations()
    }
    
    override fun clear(): Boolean {
        val update = UpdateQuery("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }")
        update(update)
        return true
    }
    
    override fun isClosed(): Boolean = false
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = false,
            supportsTransactions = false,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = true, // SPARQL 1.2 supports RDF-star
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
    
    override fun close() {
        // Nothing to close for HTTP connections
    }
    
    private fun executeQuery(sparql: String): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/sparql-query")
        connection.setRequestProperty("Accept", "application/sparql-results+json")
        connection.doOutput = true
        
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(sparql)
        writer.flush()
        
        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        return reader.readText()
    }
    
    private fun executeUpdate(sparql: String) {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/sparql-update")
        connection.doOutput = true
        
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(sparql)
        writer.flush()
        
        connection.responseCode // Read response
    }
}

class SparqlGraph(
    private val repository: SparqlRepository,
    private val graphName: Iri? = null
) : MutableRdfGraph {
    
    override fun addTriple(triple: RdfTriple) {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val update = """
            INSERT DATA {
                $graphClause {
                    <${triple.subject}> <${triple.predicate}> ${formatObject(triple.obj)}
                }
            }
        """.trimIndent()
        repository.update(UpdateQuery(update))
    }
    
    override fun addTriples(triples: Collection<RdfTriple>) {
        if (triples.isEmpty()) return
        
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val triplesClause = triples.joinToString(" .\n                    ") { triple ->
            "<${triple.subject}> <${triple.predicate}> ${formatObject(triple.obj)}"
        }
        
        val update = """
            INSERT DATA {
                $graphClause {
                    $triplesClause .
                }
            }
        """.trimIndent()
        repository.update(UpdateQuery(update))
    }
    
    override fun removeTriple(triple: RdfTriple): Boolean {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val update = """
            DELETE DATA {
                $graphClause {
                    <${triple.subject}> <${triple.predicate}> ${formatObject(triple.obj)}
                }
            }
        """.trimIndent()
        repository.update(UpdateQuery(update))
        return true
    }
    
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        if (triples.isEmpty()) return true
        
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val triplesClause = triples.joinToString(" .\n                    ") { triple ->
            "<${triple.subject}> <${triple.predicate}> ${formatObject(triple.obj)}"
        }
        
        val update = """
            DELETE DATA {
                $graphClause {
                    $triplesClause .
                }
            }
        """.trimIndent()
        repository.update(UpdateQuery(update))
        return true
    }
    
    override fun hasTriple(triple: RdfTriple): Boolean {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val query = """
            ASK {
                $graphClause {
                    <${triple.subject}> <${triple.predicate}> ${formatObject(triple.obj)}
                }
            }
        """.trimIndent()
        return repository.ask(SparqlAskQuery(query))
    }
    
    override fun getTriples(): List<RdfTriple> {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        
        val query = """
            SELECT ?s ?p ?o WHERE {
                $graphClause {
                    ?s ?p ?o .
                }
            }
        """.trimIndent()
        
        val result = repository.select(SparqlSelectQuery(query))
        return result.map { binding ->
            RdfTriple(
                binding.get("s") as RdfResource,
                binding.get("p") as Iri,
                binding.get("o") as RdfTerm
            )
        }
    }
    
    fun getTriples(subject: RdfResource? = null, predicate: Iri? = null, obj: RdfTerm? = null): List<RdfTriple> {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val subjectClause = subject?.let { "<${it}>" } ?: "?s"
        val predicateClause = predicate?.let { "<${it}>" } ?: "?p"
        val objectClause = obj?.let { formatObject(it) } ?: "?o"
        
        val query = """
            SELECT ?s ?p ?o WHERE {
                $graphClause {
                    $subjectClause $predicateClause $objectClause .
                }
            }
        """.trimIndent()
        
        val result = repository.select(SparqlSelectQuery(query))
        return result.map { binding ->
            RdfTriple(
                binding.get("s") as RdfResource,
                binding.get("p") as Iri,
                binding.get("o") as RdfTerm
            )
        }
    }
    
    override fun clear(): Boolean {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val update = """
            DELETE {
                $graphClause { ?s ?p ?o }
            } WHERE {
                $graphClause { ?s ?p ?o }
            }
        """.trimIndent()
        repository.update(UpdateQuery(update))
        return true
    }
    
    override fun size(): Int {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val query = """
            SELECT (COUNT(*) AS ?count) WHERE {
                $graphClause { ?s ?p ?o }
            }
        """.trimIndent()
        
        val result = repository.select(SparqlSelectQuery(query))
        return result.firstOrNull()?.get("count")?.let { term ->
            if (term is Literal) term.lexical.toIntOrNull() ?: 0 else 0
        } ?: 0
    }
    
    private fun formatObject(obj: RdfTerm): String {
        return when (obj) {
            is Iri -> "<${obj.value}>"
            is Literal -> {
                when (obj) {
                    is LangString -> "\"${obj.lexical}\"@${obj.lang}"
                    is TypedLiteral -> {
                        if (obj.datatype != XSD.string) {
                            "\"${obj.lexical}\"^^<${obj.datatype.value}>"
                        } else {
                            "\"${obj.lexical}\""
                        }
                    }
                    is TrueLiteral -> "\"true\"^^<${XSD.boolean.value}>"
                    is FalseLiteral -> "\"false\"^^<${XSD.boolean.value}>"
                }
            }
            is BlankNode -> "_:${obj.id}"
            is TripleTerm -> "<<${formatObject(obj.triple.subject)} ${formatObject(obj.triple.predicate)} ${formatObject(obj.triple.obj)}>>"
            else -> throw IllegalArgumentException("Unsupported RDF term type for SPARQL formatting: ${obj.javaClass.simpleName}")
        }
    }
}

class SparqlResultSet(private val response: String) : SparqlQueryResult {
    // Simple implementation - in practice would parse SPARQL JSON results
    override fun iterator(): Iterator<BindingSet> = emptyList<BindingSet>().iterator()
    override fun toList(): List<BindingSet> = emptyList()
    override fun first(): BindingSet? = null
    override fun count(): Int = 0
    override fun asSequence(): Sequence<BindingSet> = emptySequence()
}









