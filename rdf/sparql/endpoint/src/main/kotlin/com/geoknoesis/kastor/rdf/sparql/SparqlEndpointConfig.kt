package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.ProviderCapabilities
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.time.Duration
import java.util.Base64

/** How SPARQL queries are sent (SPARQL 1.1 Protocol §2.1). */
enum class SparqlQueryMethod {
    /** `POST` with an `application/sparql-query` body (default). */
    POST,
    /** `POST` with an `application/x-www-form-urlencoded` body (`query=...`). */
    POST_FORM,
    /** `GET` with a `query=...` URL parameter. */
    GET,
}

/** How SPARQL updates are sent (SPARQL 1.1 Protocol §2.2). */
enum class SparqlUpdateMethod {
    /** `POST` with an `application/sparql-update` body (default). */
    POST,
    /** `POST` with an `application/x-www-form-urlencoded` body (`update=...`). */
    POST_FORM,
}

/**
 * Connection settings for [SparqlRepository].
 *
 * @property endpoint query endpoint (`http` or `https`). Credentials embedded in the URL
 *   (`https://user:pass@host/sparql`) are removed from the request URL and sent as HTTP Basic
 *   authentication unless [username] is set.
 * @property updateEndpoint update endpoint; defaults to [endpoint].
 * @property maxResponseBytes cap for responses that are read into memory: [SparqlRepository.select],
 *   ASK, UPDATE responses.
 * @property maxStreamedResponseBytes cap for [SparqlRepository.withSelectRows], whose rows are
 *   streamed to the consumer; `null` (default) means unbounded.
 * @property connectTimeout TCP connect timeout.
 * @property readTimeout maximum wait for any single read from the socket.
 * @property requestTimeout overall deadline for one request, including reading/consuming the
 *   whole response; a watchdog disconnects requests that exceed it. `null` disables the deadline.
 * @property headers extra HTTP headers sent with every request (e.g. API keys).
 * @property username HTTP Basic user; requires [password].
 * @property insertBatchSize maximum triples per `INSERT DATA`/`DELETE DATA` request for data without
 *   blank nodes.
 */
data class SparqlEndpointConfig(
    val endpoint: String,
    val updateEndpoint: String? = null,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val maxStreamedResponseBytes: Long? = null,
    val connectTimeout: Duration = Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MILLIS.toLong()),
    val readTimeout: Duration = Duration.ofMillis(DEFAULT_READ_TIMEOUT_MILLIS.toLong()),
    val requestTimeout: Duration? = Duration.ofMinutes(DEFAULT_REQUEST_TIMEOUT_MINUTES),
    val headers: Map<String, String> = emptyMap(),
    val username: String? = null,
    val password: String? = null,
    val queryMethod: SparqlQueryMethod = SparqlQueryMethod.POST,
    val updateMethod: SparqlUpdateMethod = SparqlUpdateMethod.POST,
    val insertBatchSize: Int = DEFAULT_INSERT_BATCH_SIZE,
) {
    init {
        HttpTarget.parse(endpoint)
        updateEndpoint?.let(HttpTarget::parse)
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(maxStreamedResponseBytes == null || maxStreamedResponseBytes > 0) { "maxStreamedResponseBytes must be positive or null" }
        requirePositive(connectTimeout, "connectTimeout")
        requirePositive(readTimeout, "readTimeout")
        requestTimeout?.let { requirePositive(it, "requestTimeout") }
        require(insertBatchSize > 0) { "insertBatchSize must be positive" }
        require((username == null) == (password == null)) { "username and password must be set together" }
        headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name: '$name'" }
            require(value.none { it == '\r' || it == '\n' }) { "HTTP header '$name' must not contain line breaks" }
        }
    }

    /** Never prints passwords or credentials embedded in endpoint URLs. */
    override fun toString(): String =
        "SparqlEndpointConfig(endpoint=${HttpTarget.redact(endpoint)}, updateEndpoint=${updateEndpoint?.let(HttpTarget::redact)}, " +
            "maxResponseBytes=$maxResponseBytes, maxStreamedResponseBytes=$maxStreamedResponseBytes, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, requestTimeout=$requestTimeout, " +
            "headers=${headers.keys}, username=$username, password=${password?.let { "***" }}, " +
            "queryMethod=$queryMethod, updateMethod=$updateMethod, insertBatchSize=$insertBatchSize)"

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 30_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 60_000
        const val DEFAULT_REQUEST_TIMEOUT_MINUTES: Long = 5
        const val DEFAULT_INSERT_BATCH_SIZE: Int = 5_000

        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        /**
         * Build a configuration from provider options ([com.geoknoesis.kastor.rdf.RdfConfig.options]):
         * `location` (required), `updateLocation`, `maxResponseBytes`, `maxStreamedResponseBytes`,
         * `connectTimeoutMillis`, `readTimeoutMillis`, `requestTimeoutMillis`, `username`, `password`,
         * `queryMethod` (`POST`/`POST_FORM`/`GET`), `updateMethod` (`POST`/`POST_FORM`),
         * `insertBatchSize`, and `header.<Name>` for custom headers. `maxStreamedResponseBytes` and
         * `requestTimeoutMillis` accept `none` for unbounded.
         */
        fun fromOptions(options: Map<String, String>): SparqlEndpointConfig {
            val endpoint = options["location"] ?: throw IllegalArgumentException("SPARQL endpoint URL required (option 'location')")
            fun long(key: String): Long? = options[key]?.let {
                it.trim().toLongOrNull() ?: throw IllegalArgumentException("Option '$key' must be a number, got '$it'")
            }
            fun optionalLong(key: String, default: Long?): Long? =
                if (options[key]?.trim()?.equals("none", ignoreCase = true) == true) null else long(key) ?: default
            fun <E : Enum<E>> enum(key: String, values: Array<E>, default: E): E = options[key]?.let { raw ->
                values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("Option '$key' must be one of ${values.joinToString()}, got '$raw'")
            } ?: default

            val defaults = SparqlEndpointConfig(endpoint)
            return SparqlEndpointConfig(
                endpoint = endpoint,
                updateEndpoint = options["updateLocation"],
                maxResponseBytes = long("maxResponseBytes") ?: defaults.maxResponseBytes,
                maxStreamedResponseBytes = optionalLong("maxStreamedResponseBytes", defaults.maxStreamedResponseBytes),
                connectTimeout = long("connectTimeoutMillis")?.let(Duration::ofMillis) ?: defaults.connectTimeout,
                readTimeout = long("readTimeoutMillis")?.let(Duration::ofMillis) ?: defaults.readTimeout,
                requestTimeout = optionalLong("requestTimeoutMillis", defaults.requestTimeout?.toMillis())?.let(Duration::ofMillis),
                headers = options.filterKeys { it.startsWith("header.") }.mapKeys { it.key.removePrefix("header.") },
                username = options["username"],
                password = options["password"],
                queryMethod = enum("queryMethod", SparqlQueryMethod.values(), defaults.queryMethod),
                updateMethod = enum("updateMethod", SparqlUpdateMethod.values(), defaults.updateMethod),
                insertBatchSize = long("insertBatchSize")?.let(Math::toIntExact) ?: defaults.insertBatchSize,
            )
        }

        private fun requirePositive(duration: Duration, name: String) =
            require(!duration.isNegative && !duration.isZero) { "$name must be positive" }
    }
}

/**
 * What this HTTP adapter itself supports. The remote server may support more (SPARQL 1.2,
 * federation, ...), but this client only speaks SPARQL 1.1 Protocol with JSON results, does not
 * decode triple terms or directional literals, and has no transactions.
 */
internal val SPARQL_ENDPOINT_CAPABILITIES = ProviderCapabilities(
    supportsInference = false,
    supportsTransactions = false,
    supportsNamedGraphs = true,
    supportsUpdates = true,
    supportsRdfStar = false,
    supportsTripleTerms = false,
    maxMemoryUsage = Long.MAX_VALUE,
    sparqlVersion = "1.1",
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsSubSelect = true,
    supportsFederation = false,
    supportsVersionDeclaration = false,
    supportsServiceDescription = true,
)

/** A validated http(s) endpoint with URL-embedded credentials split off. */
internal class HttpTarget private constructor(val url: URL, val userInfoAuthorization: String?) {

    /** [url] with `name=value` appended to its query string. */
    fun withParameter(name: String, value: String): URL {
        val base = url.toString()
        val separator = if (url.query == null) "?" else "&"
        return URI("$base$separator$name=${formEncode(value)}").toURL()
    }

    companion object {
        fun parse(endpoint: String): HttpTarget {
            val uri = try {
                URI(endpoint)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Invalid SPARQL endpoint URL: ${redact(endpoint)}", e)
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") {
                "SPARQL endpoint must be an http(s) URL, got '${redact(endpoint)}'"
            }
            require(!uri.host.isNullOrEmpty()) { "SPARQL endpoint URL has no host: '${redact(endpoint)}'" }
            val authorization = uri.userInfo?.let { info ->
                basicAuthorization(info.substringBefore(':'), if (':' in info) info.substringAfter(':') else "")
            }
            val clean = uri.rawUserInfo?.let { endpoint.replaceFirst("$it@", "") } ?: endpoint
            return HttpTarget(URI(clean).toURL(), authorization)
        }

        fun redact(endpoint: String): String {
            val userInfo = try { URI(endpoint).rawUserInfo } catch (_: URISyntaxException) { null }
            return if (userInfo == null) endpoint else endpoint.replaceFirst("$userInfo@", "***@")
        }

        fun basicAuthorization(user: String, password: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

        fun formEncode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
