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

// Bound network waits so a slow/hostile endpoint cannot pin a thread indefinitely.
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000

class SparqlRepository(
    private val endpoint: String,
    private val maxResponseBytes: Long = 32L * 1024 * 1024,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
) : RdfRepository {
    @Volatile private var closed = false
    init { require(maxResponseBytes > 0); require(connectTimeoutMillis > 0); require(readTimeoutMillis > 0) }

    
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
        val existed = hasGraph(name)
        val updateQuery = UpdateQuery("DROP SILENT GRAPH <${name.value}>")
        update(updateQuery)
        return existed
    }

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult = withSelectRows(query) { ListSparqlQueryResult(it.toList()) }
    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T =
        queryResponse(query.sparql) { input -> consume(JsonBindingRows(input).rows().map(SparqlJsonResults::row)) }

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
        throw UnsupportedOperationException("The HTTP endpoint does not provide atomic transactions")
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        throw UnsupportedOperationException("The HTTP endpoint does not provide atomic transactions")
    }
    
    override fun clear(): Boolean {
        val existed = ask(SparqlAskQuery("ASK { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }"))
        val updateQuery = UpdateQuery("CLEAR ALL")
        update(updateQuery)
        return existed
    }
    
    override fun isClosed(): Boolean = closed
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = false,
            supportsTransactions = false,
            supportsNamedGraphs = true,
            supportsUpdates = true,
            supportsRdfStar = false,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
    
    override fun close() {
        closed = true
    }
    
    private fun executeQuery(sparql: String): String = queryResponse(sparql) { it.reader(Charsets.UTF_8).readText() }

    private fun <T> queryResponse(sparql: String, consume: (java.io.InputStream) -> T): T {
        check(!closed) { "Repository is closed" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/sparql-query")
            setRequestProperty("Accept", "application/sparql-results+json")
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(sparql.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.use { String(it.readNBytes(512), Charsets.UTF_8) }.orEmpty()
                throw RdfQueryException("HTTP ${connection.responseCode}: $error", query = sparql)
            }
            return connection.inputStream.use { raw ->
                val bounded = object : java.io.FilterInputStream(raw) {
                    var count = 0L
                    fun counted(n: Int): Int { if (n > 0) count += n; check(count <= maxResponseBytes) { "SPARQL response exceeds $maxResponseBytes bytes" }; return n }
                    override fun read(): Int { val b = `in`.read(); if (b >= 0) counted(1); return b }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = counted(`in`.read(b, off, len))
                }
                consume(bounded)
            }
        } catch (e: RdfQueryException) { throw e
        } catch (e: Exception) { throw RdfQueryException("SPARQL response failed: ${e.message}", query = sparql, cause = e)
        } finally { connection.disconnect() }
    }

    private fun executeUpdate(sparql: String) {
        check(!closed) { "Repository is closed" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/sparql-update")
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(sparql.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.use { String(it.readNBytes(512), Charsets.UTF_8) }.orEmpty()
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
    
    private fun pattern(body: String): String = if (graphName == null) body else "GRAPH ${iriRef(graphName.value)} { $body }"
    private fun hasBlank(t: RdfTriple): Boolean = t.subject is BlankNode || t.obj is BlankNode
    private fun rendered(t: RdfTriple): String = "${formatSubject(t.subject)} ${formatPredicate(t.predicate)} ${formatObject(t.obj)} ."
    override fun addTriple(triple: RdfTriple) {
        require(!hasBlank(triple)) { "Insert connected blank-node data in one addTriples call; labels are scoped to a request" }
        addTriples(listOf(triple))
    }
    override fun addTriples(triples: Collection<RdfTriple>) {
        if (triples.isEmpty()) return
        repository.update(UpdateQuery("INSERT DATA { ${pattern(triples.joinToString("\n", transform = ::rendered))} }"))
    }
    override fun removeTriple(triple: RdfTriple): Boolean = removeTriples(listOf(triple))
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        if (triples.isEmpty()) return false
        require(triples.none(::hasBlank)) { "Blank-node identity cannot be addressed by DELETE DATA; use an explicit DELETE WHERE pattern" }
        val existed = triples.any(::hasTriple)
        repository.update(UpdateQuery("DELETE DATA { ${pattern(triples.joinToString("\n", transform = ::rendered))} }"))
        return existed
    }

    override fun hasTriple(triple: RdfTriple): Boolean {
        require(!hasBlank(triple)) { "Remote blank-node identifiers cannot be used as query constants" }
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
    
    override fun getTriples(): List<RdfTriple> = find()
    fun getTriples(subject: RdfResource? = null, predicate: Iri? = null, obj: RdfTerm? = null): List<RdfTriple> = find(subject, predicate, obj)
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
        require(subject !is BlankNode && obj !is BlankNode) { "Remote blank-node identifiers cannot be used as query constants" }
        val body = "${subject?.let(::formatSubject) ?: "?s"} ${predicate?.let(::formatPredicate) ?: "?p"} ${obj?.let(::formatObject) ?: "?o"} ."
        val result = repository.select(SparqlSelectQuery("SELECT * WHERE { ${pattern(body)} }"))
        return result.map { row -> RdfTriple(subject ?: row.get("s") as RdfResource,
            predicate ?: row.get("p") as Iri, obj ?: row.get("o") as RdfTerm) }
    }

    override fun clear(): Boolean {
        val existed = repository.ask(SparqlAskQuery("ASK { ${pattern("?s ?p ?o")} }"))
        repository.update(UpdateQuery(if (graphName == null) "CLEAR DEFAULT" else "CLEAR SILENT GRAPH ${iriRef(graphName.value)}"))
        return existed
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
            if (term is Literal) Math.toIntExact(term.lexical.toLong()) else 0
        } ?: 0
    }
    
    private fun formatSubject(subject: RdfResource): String = when (subject) {
        is Iri -> iriRef(subject.value)
        is BlankNode -> bnodeLabel(subject.id)
    }

    private fun formatPredicate(predicate: Iri): String = iriRef(predicate.value)

    private fun formatObject(obj: RdfTerm): String {
        return when (obj) {
            is Iri -> iriRef(obj.value)
            is Literal -> {
                when (obj) {
                    is LangString -> {
                        require(obj.direction == null) { "This HTTP adapter does not support directional literals" }
                        "\"${escapeLiteral(obj.lexical)}\"@${langTag(obj.lang)}"
                    }
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
            is BlankNode -> bnodeLabel(obj.id)
            is TripleTerm -> throw UnsupportedOperationException("Configure a provider with RDF 1.2 support for triple terms")
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

    /**
     * Validate a language tag before interpolating it into a SPARQL string. A valid
     * BCP-47 tag only contains letters, digits and hyphens, so this both enforces
     * well-formedness and prevents a tag from a hostile endpoint breaking out of the
     * literal (e.g. `en" . DROP ...`).
     */
    private fun langTag(lang: String): String {
        require(SAFE_LANG_TAG.matches(lang)) { "Invalid language tag for SPARQL: '$lang'" }
        return lang
    }

    /** Validate a blank-node label so it cannot inject SPARQL (no whitespace, braces, dots-as-terminators). */
    private fun bnodeLabel(id: String): String {
        require(SAFE_BNODE_LABEL.matches(id)) { "Unsafe blank node label for SPARQL: '$id'" }
        return "_:$id"
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
        private val SAFE_LANG_TAG = Regex("[A-Za-z]+(?:-[A-Za-z0-9]+)*")
        // Start with letter/digit/underscore, end with letter/digit/underscore/hyphen
        // (not a dot, which SPARQL would read as a statement terminator).
        private val SAFE_BNODE_LABEL = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?")
    }
}

/**
 * Parser for the SPARQL 1.1 Query Results JSON Format (application/sparql-results+json).
 */
private object SparqlJsonResults {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

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
        val bindingsArray = root["results"]?.jsonObject?.get("bindings")?.jsonArray ?: error("SPARQL SELECT response missing results.bindings")
        return bindingsArray.map { row(it.jsonObject) }
    }
    fun row(row: JsonObject): BindingSet = MapBindingSet(row.mapValues { (_, value) -> termFromBinding(value.jsonObject) })

    private fun termFromBinding(binding: JsonObject): RdfTerm {
        val type = binding["type"]?.jsonPrimitive?.contentOrNull ?: error("SPARQL binding missing required field")
        val value = binding["value"]?.jsonPrimitive?.contentOrNull ?: error("SPARQL binding missing required field")
        return when (type) {
            "uri" -> Iri(value)
            "bnode" -> BlankNode(value)
            "literal", "typed-literal" -> {
                require(binding["its:dir"] == null && binding["direction"] == null) { "Directional result literals are unsupported" }
                val lang = binding["xml:lang"]?.jsonPrimitive?.contentOrNull
                val datatype = binding["datatype"]?.jsonPrimitive?.contentOrNull
                when {
                    lang != null -> LangString(value, lang)
                    datatype != null -> Literal(value, Iri(datatype))
                    else -> Literal(value, XSD.string)
                }
            }
            else -> error("Unsupported SPARQL result binding type: $type")
        }
    }
}









