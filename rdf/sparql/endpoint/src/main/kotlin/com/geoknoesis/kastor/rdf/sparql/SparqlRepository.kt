package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.FalseLiteral
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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
        val updateQuery = UpdateQuery("DROP GRAPH <${name.value}>")
        update(updateQuery)
        return true
    }

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult {
        val startTime = System.currentTimeMillis()
        try {
            val response = executeQuery(query.sparql)
            val rows = SparqlJsonResults.parseSelect(response)
            val resultSet = ListSparqlQueryResult(rows)
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("SELECT", query.sparql, null, executionTime, rows.size)
            return resultSet
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryError("SELECT", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        val startTime = System.currentTimeMillis()
        try {
            val response = executeQuery(query.sparql)
            // Parse the SPARQL Results JSON `{ "boolean": true }` form; fall back to
            // the plain-text `true`/`false` some endpoints return.
            val result = SparqlJsonResults.parseAsk(response)
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("ASK", query.sparql, null, executionTime, if (result) 1 else 0)
            return result
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }
    
    /**
     * CONSTRUCT/DESCRIBE return an RDF graph serialization (Turtle/N-Triples/…),
     * which requires an RDF parser. This endpoint adapter lives in `:rdf:core`,
     * which intentionally has no parser, so we fail loudly rather than silently
     * returning an empty graph (which previously masked the gap). Run
     * CONSTRUCT/DESCRIBE through a provider-backed repository (Jena/RDF4J) instead.
     */
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> =
        throw UnsupportedOperationException(
            "CONSTRUCT over a remote SPARQL endpoint is not supported by the core-only HTTP adapter; " +
                "use a Jena/RDF4J-backed repository to parse the returned RDF graph."
        )

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> =
        throw UnsupportedOperationException(
            "DESCRIBE over a remote SPARQL endpoint is not supported by the core-only HTTP adapter; " +
                "use a Jena/RDF4J-backed repository to parse the returned RDF graph."
        )
    
    override fun update(query: UpdateQuery) {
        val startTime = System.currentTimeMillis()
        try {
            executeUpdate(query.sparql)
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("UPDATE", query.sparql, null, executionTime, null)
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryError("UPDATE", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL UPDATE: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
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
        val updateQuery = UpdateQuery("DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }")
        update(updateQuery)
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
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/sparql-query")
            setRequestProperty("Accept", "application/sparql-results+json")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(sparql.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.use { it.reader(Charsets.UTF_8).readText() }.orEmpty()
                throw RdfQueryException(
                    message = "SPARQL endpoint returned HTTP $status: ${error.take(500)}",
                    query = sparql,
                )
            }
            return connection.inputStream.use { it.reader(Charsets.UTF_8).readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun executeUpdate(sparql: String) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/sparql-update")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(sparql.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.use { it.reader(Charsets.UTF_8).readText() }.orEmpty()
                throw RdfQueryException(
                    message = "SPARQL endpoint returned HTTP $status: ${error.take(500)}",
                    query = sparql,
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}

class SparqlGraph(
    private val repository: SparqlMutable,
    private val graphName: Iri? = null
) : MutableRdfGraph, SourceTrackedGraph {
    
    override val sourceRepository: RdfRepository? 
        get() = repository as? RdfRepository
    override val sourceGraphName: Iri? = graphName
    
    override fun addTriple(triple: RdfTriple) {
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val update = """
            INSERT DATA {
                $graphClause {
                    ${formatSubject(triple.subject)} ${formatPredicate(triple.predicate)} ${formatObject(triple.obj)}
                }
            }
        """.trimIndent()
        repository.update(UpdateQuery(update))
    }
    
    override fun addTriples(triples: Collection<RdfTriple>) {
        if (triples.isEmpty()) return
        
        val graphClause = if (graphName != null) "GRAPH <${graphName.value}>" else ""
        val triplesClause = triples.joinToString(" .\n                    ") { triple ->
            "${formatSubject(triple.subject)} ${formatPredicate(triple.predicate)} ${formatObject(triple.obj)}"
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
                    ${formatSubject(triple.subject)} ${formatPredicate(triple.predicate)} ${formatObject(triple.obj)}
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
            "${formatSubject(triple.subject)} ${formatPredicate(triple.predicate)} ${formatObject(triple.obj)}"
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
                    ${formatSubject(triple.subject)} ${formatPredicate(triple.predicate)} ${formatObject(triple.obj)}
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
        val subjectClause = subject?.let { formatSubject(it) } ?: "?s"
        val predicateClause = predicate?.let { formatPredicate(it) } ?: "?p"
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
    
    private fun formatSubject(subject: RdfResource): String = when (subject) {
        is Iri -> iriRef(subject.value)
        is BlankNode -> "_:${subject.id}"
    }

    private fun formatPredicate(predicate: Iri): String = iriRef(predicate.value)

    private fun formatObject(obj: RdfTerm): String {
        return when (obj) {
            is Iri -> iriRef(obj.value)
            is Literal -> {
                when (obj) {
                    is LangString -> "\"${escapeLiteral(obj.lexical)}\"@${obj.lang}"
                    is TypedLiteral -> {
                        if (obj.datatype != XSD.string) {
                            "\"${escapeLiteral(obj.lexical)}\"^^${iriRef(obj.datatype.value)}"
                        } else {
                            "\"${escapeLiteral(obj.lexical)}\""
                        }
                    }
                    is TrueLiteral -> "\"true\"^^${iriRef(XSD.boolean.value)}"
                    is FalseLiteral -> "\"false\"^^${iriRef(XSD.boolean.value)}"
                }
            }
            is BlankNode -> "_:${obj.id}"
            is TripleTerm -> "<<${formatSubject(obj.triple.subject)} ${formatPredicate(obj.triple.predicate)} ${formatObject(obj.triple.obj)}>>"
            else -> throw IllegalArgumentException("Unsupported RDF term type for SPARQL formatting: ${obj.javaClass.simpleName}")
        }
    }

    /** Render an IRI as a SPARQL IRIREF, rejecting characters that would break out of `<...>`. */
    private fun iriRef(value: String): String {
        require(value.none { it.isWhitespace() || it in ILLEGAL_IRI_CHARS }) {
            "IRI contains characters illegal in a SPARQL IRIREF: '$value'"
        }
        return "<$value>"
    }

    /** Escape a string literal's lexical form for inclusion inside `"..."` per Turtle/SPARQL rules. */
    private fun escapeLiteral(lexical: String): String = buildString(lexical.length) {
        for (c in lexical) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private companion object {
        private val ILLEGAL_IRI_CHARS = setOf('<', '>', '"', '{', '}', '|', '^', '`', '\\')
    }
}

/**
 * Parser for the SPARQL 1.1 Query Results JSON Format (application/sparql-results+json).
 */
private object SparqlJsonResults {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseAsk(response: String): Boolean {
        val trimmed = response.trim()
        // Some endpoints return a bare `true`/`false` for ASK.
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        val root = json.parseToJsonElement(trimmed).jsonObject
        return root["boolean"]?.jsonPrimitive?.booleanOrNull
            ?: error("SPARQL ASK response missing 'boolean' field")
    }

    fun parseSelect(response: String): List<BindingSet> {
        val root = json.parseToJsonElement(response).jsonObject
        val bindingsArray = root["results"]?.jsonObject?.get("bindings")?.jsonArray ?: return emptyList()
        return bindingsArray.map { row ->
            val values = LinkedHashMap<String, RdfTerm>()
            row.jsonObject.forEach { (variable, binding) ->
                termFromBinding(binding.jsonObject)?.let { values[variable] = it }
            }
            MapBindingSet(values)
        }
    }

    private fun termFromBinding(binding: JsonObject): RdfTerm? {
        val type = binding["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val value = binding["value"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (type) {
            "uri" -> Iri(value)
            "bnode" -> BlankNode(value)
            "literal", "typed-literal" -> {
                val lang = binding["xml:lang"]?.jsonPrimitive?.contentOrNull
                val datatype = binding["datatype"]?.jsonPrimitive?.contentOrNull
                when {
                    lang != null -> LangString(value, lang)
                    datatype != null -> Literal(value, Iri(datatype))
                    else -> Literal(value, XSD.string)
                }
            }
            else -> null
        }
    }
}









