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
    /**
     * `GET` with a `query=...` URL parameter. Requests whose URL would exceed
     * [SparqlEndpointConfig.maxGetUrlLength] are sent as [POST_FORM] instead.
     */
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
 * What happens to a SELECT result row that holds a term the RDF model refuses: an IRI (of a `uri`
 * binding or of a literal's `datatype`) that is not an absolute IRI or contains characters an IRI
 * must not contain, a language tag that is not well-formed, a blank node without a label, or text
 * with an unpaired surrogate escape. Endpoints do return such terms: they store what was loaded,
 * relative IRIs and IRIs with spaces included.
 *
 * The policy only concerns terms. A response that is not JSON, or not a SPARQL 1.1 JSON result
 * (missing or repeated members, members of the wrong type, variables that `head` does not declare,
 * RDF 1.2 terms), fails under every policy, as do I/O errors and the size limits.
 */
enum class MalformedTermPolicy {
    /** The query fails with [com.geoknoesis.kastor.rdf.RdfQueryException] at the first such row (default). */
    FAIL,

    /**
     * The whole row is left out of the result and the remaining rows are delivered. A row is never
     * delivered with the offending variable unbound, because that would be indistinguishable from a
     * solution in which the variable really is unbound (an `OPTIONAL` that did not match). The
     * adapter logs one warning per query (logger `com.geoknoesis.kastor.rdf.sparql.SparqlRepository`)
     * with the number of rows skipped and the first offending term.
     *
     * Skipped rows are not counted or reported in any other way: aggregates computed by the endpoint
     * (`COUNT`, and therefore [SparqlGraph.size]) still include them, and `LIMIT`/`OFFSET` apply
     * before they are skipped, so a page may hold fewer rows than requested.
     */
    SKIP_ROW,
}

/**
 * Connection settings for [SparqlRepository].
 *
 * @property endpoint query endpoint (`http` or `https`, without a `#fragment`). Credentials embedded in the URL
 *   (`https://user:pass@host/sparql`) are removed from the request URL and sent as HTTP Basic
 *   authentication unless [username] is set.
 * @property updateEndpoint update endpoint; defaults to [endpoint].
 * @property maxResponseBytes cap for responses that are read into memory: [SparqlRepository.select],
 *   ASK, UPDATE responses.
 * @property maxStreamedResponseBytes cap for [SparqlRepository.withSelectRows], whose rows are
 *   streamed to the consumer; `null` (default) means unbounded.
 * @property connectTimeout TCP connect timeout. Like every timeout here it must be positive; a
 *   duration too long to be counted in nanoseconds (about 292 years, so
 *   `Duration.ofMillis(Long.MAX_VALUE)` or `ChronoUnit.FOREVER.duration`) means "no limit".
 * @property readTimeout maximum wait for any single read of the response body. When
 *   [requestTimeout] is `null` it also bounds the wait for the response headers: once for the whole
 *   request, however many redirects are followed (not once per redirect).
 * @property requestTimeout deadline for one request. For buffered calls (select, ASK, UPDATE) it
 *   covers the whole exchange including reading the response. For streamed rows
 *   ([SparqlRepository.withSelectRows] without a per-call timeout) it only covers the time until the
 *   response headers arrive (all redirects included), so a slow row consumer is never cut off by
 *   it. `null` disables the deadline (the wait for response headers is then bounded by
 *   [readTimeout]). A per-call timeout passed to `withSelectRows(query, bindings, timeout)` replaces
 *   it and bounds the whole call. The adapter measures these waits itself and sets no timer on the
 *   HTTP request, so the behaviour does not depend on how the JDK's HTTP client interprets a request
 *   timeout (from JDK 26 it also covers reading the response body).
 * @property headers extra HTTP headers sent with every request (e.g. API keys). Never sent to a
 *   different origin after a redirect. Names must be RFC 9110 tokens; values may hold visible ASCII,
 *   ISO-8859-1 characters, and spaces or tabs between them (no other control character, and no
 *   leading or trailing white space). The headers the adapter or the HTTP client sets itself are
 *   refused: `Accept` and `Content-Type` (a custom value would make every request or response
 *   unreadable), `Content-Length`, `Transfer-Encoding`, `Host`, `Connection`, `Expect` and
 *   `Upgrade`. An `Authorization` header (a bearer token, say) is allowed, but not together with
 *   [username] or with credentials in an endpoint URL. All of this is checked when the
 *   configuration is created. A warning is logged once per endpoint when a header whose name says
 *   that it carries a credential (`Authorization`, `Cookie`, `X-API-Key`, ...) would be sent over
 *   plain `http`.
 * @property username HTTP Basic user; requires [password]. A warning is logged once per endpoint
 *   when Basic credentials would be sent over plain `http`.
 * @property insertBatchSize maximum triples per `INSERT DATA`/`DELETE DATA` request. Triples joined
 *   by blank nodes are never split across requests; see [SparqlGraph].
 * @property streamingRequestTimeout optional overall deadline for a streamed
 *   [SparqlRepository.withSelectRows] call, including the wait for the response headers and the
 *   time the consumer spends on rows; when it is shorter than [requestTimeout] (or a per-call
 *   timeout) it also ends the header wait. `null` (default) means only [requestTimeout] (until
 *   headers) and [readTimeout] (per read) apply.
 * @property followCrossOriginRedirects follow `307`/`308` (and, for GET, `301`/`302`/`303`)
 *   redirects to a different scheme, host or port. Custom [headers] and credentials are never sent
 *   to the other origin (a warning is logged when they are dropped). Default `false`: such
 *   redirects fail with a clear error. A redirect from `https` to plain `http` is always refused,
 *   because the query would be re-sent unencrypted.
 * @property maxRedirects maximum redirects followed per request.
 * @property maxGetUrlLength longest request URL sent with [SparqlQueryMethod.GET]; longer queries
 *   are sent as a form-encoded POST instead (many servers and proxies reject long URLs).
 * @property maxBlankNodeComponentTriples largest group of triples connected through blank nodes that
 *   [SparqlGraph.addTriples] accepts. Such a group must be sent in one request because blank-node
 *   labels are scoped to a request; larger groups are rejected before anything is sent.
 * @property maxResultRowChars largest single JSON value that is read while parsing SELECT results:
 *   one binding row, or one skipped value such as `head`. It is measured in characters of the JSON
 *   text (UTF-16 code units, so a character outside the Basic Multilingual Plane counts as two;
 *   quotes, escapes and white space inside the value count too). It applies to
 *   [SparqlRepository.select] and [SparqlRepository.withSelectRows] alike, so a streamed response
 *   with [maxStreamedResponseBytes] `null` still never holds an unbounded row. A value of exactly
 *   this size is accepted; a longer one fails with [com.geoknoesis.kastor.rdf.RdfQueryException].
 *   Default 4 Mi characters. A row is decoded once, straight from the stream, and only what its
 *   bindings need is kept: the variable names and each term's `type`, `value`, `xml:lang` and
 *   `datatype`. Of `head` only the variable names of `vars` are kept (`head` is one value, so they
 *   are bounded by this limit too); unknown members and nested values are checked and skipped
 *   without being stored, however large they are. The heap a row needs is therefore that of its decoded strings
 *   (at most two bytes per character of the row, 8 MB at the default), plus roughly 200 bytes for
 *   every variable it binds (a binding takes at least 27 characters, so a row made of nothing but
 *   minimal bindings stays below 8 bytes per character, about 32 MB at the default), plus a scratch
 *   buffer of up to twice the size of its largest string while that string is being read.
 *   Values may be nested at most 128 levels deep.
 * @property strictContentType whether a successful SELECT/ASK response must declare a JSON media
 *   type (`application/sparql-results+json`, `application/json`, any `+json` type; `text/plain` is
 *   also accepted for ASK; a response without a Content-Type is always parsed). Default `true`: any
 *   other type, such as the HTML of a login page, fails with
 *   [com.geoknoesis.kastor.rdf.RdfQueryException] naming the type. Set it to `false` for a legacy
 *   server that labels its JSON results `text/json`, `application/javascript` or similar: the
 *   Content-Type is then ignored and the body is parsed as SPARQL JSON results (a body that is not
 *   such JSON still fails, as a result-format error).
 * @property malformedTerms what happens to a SELECT result row with a term the RDF model refuses
 *   (an invalid IRI, for example). Default [MalformedTermPolicy.FAIL]: the query fails at that row,
 *   which for a streamed result is after the rows before it were delivered.
 *   [MalformedTermPolicy.SKIP_ROW] leaves such rows out and logs one warning per query; it is the
 *   counterpart of the RDF4J provider's `lenientRead` for graph reads. See [MalformedTermPolicy].
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
    val streamingRequestTimeout: Duration? = null,
    val followCrossOriginRedirects: Boolean = false,
    val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    val maxGetUrlLength: Int = DEFAULT_MAX_GET_URL_LENGTH,
    val maxBlankNodeComponentTriples: Int = DEFAULT_MAX_BLANK_NODE_COMPONENT_TRIPLES,
    val maxResultRowChars: Int = DEFAULT_MAX_RESULT_ROW_CHARS,
    val strictContentType: Boolean = true,
    val malformedTerms: MalformedTermPolicy = MalformedTermPolicy.FAIL,
) {
    init {
        HttpTarget.parse(endpoint)
        updateEndpoint?.let(HttpTarget::parse)
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(maxStreamedResponseBytes == null || maxStreamedResponseBytes > 0) { "maxStreamedResponseBytes must be positive or null" }
        requirePositive(connectTimeout, "connectTimeout")
        requirePositive(readTimeout, "readTimeout")
        requestTimeout?.let { requirePositive(it, "requestTimeout") }
        streamingRequestTimeout?.let { requirePositive(it, "streamingRequestTimeout") }
        require(insertBatchSize > 0) { "insertBatchSize must be positive" }
        require(maxRedirects >= 0) { "maxRedirects must not be negative" }
        require(maxGetUrlLength > 0) { "maxGetUrlLength must be positive" }
        require(maxBlankNodeComponentTriples > 0) { "maxBlankNodeComponentTriples must be positive" }
        require(maxResultRowChars > 0) { "maxResultRowChars must be positive" }
        require((username == null) == (password == null)) { "username and password must be set together" }
        if (headers.keys.any { it.equals("Authorization", ignoreCase = true) }) {
            // Two sources for one header: neither is silently dropped.
            require(username == null) { "Set either username and password or an Authorization header, not both" }
            require(listOfNotNull(endpoint, updateEndpoint).none { HttpTarget.parse(it).userInfoAuthorization != null }) {
                "Set either credentials in the endpoint URL or an Authorization header, not both"
            }
        }
        headers.forEach { (name, value) ->
            // Checked here in full, so that a request is never refused when it is built.
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name: '${printable(name)}'" }
            require(name.lowercase() !in RESTRICTED_HEADERS) { "HTTP header '$name' is managed by the HTTP client and cannot be set" }
            ADAPTER_HEADERS[name.lowercase()]?.let { why -> throw IllegalArgumentException("HTTP header '$name' cannot be set: $why") }
            require(value.none { it == '\r' || it == '\n' }) { "HTTP header '$name' must not contain line breaks" }
            require(value.all(::isFieldValueChar)) {
                "HTTP header '$name' has an invalid value: only visible ASCII, space, tab and ISO-8859-1 characters are allowed (RFC 9110)"
            }
            require(value.isEmpty() || (!isFieldSpace(value.first()) && !isFieldSpace(value.last()))) {
                "HTTP header '$name' has an invalid value: it must not start or end with a space or tab (RFC 9110)"
            }
        }
    }

    /**
     * The names of the custom [headers] that carry a credential, judging by the name: `Authorization`,
     * `Proxy-Authorization`, `Cookie`, and any name with `auth`, `key`, `token`, `secret`, `password`,
     * `credential` or `session` in it (`X-API-Key`, `X-Auth-Token`, ...).
     */
    internal fun credentialHeaders(): List<String> = headers.keys.filter { name ->
        val lower = name.lowercase()
        lower == "cookie" || CREDENTIAL_WORDS.any { it in lower }
    }

    /** Never prints passwords or credentials embedded in endpoint URLs. */
    override fun toString(): String =
        "SparqlEndpointConfig(endpoint=${HttpTarget.redact(endpoint)}, updateEndpoint=${updateEndpoint?.let(HttpTarget::redact)}, " +
            "maxResponseBytes=$maxResponseBytes, maxStreamedResponseBytes=$maxStreamedResponseBytes, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, requestTimeout=$requestTimeout, " +
            "headers=${headers.keys}, username=$username, password=${password?.let { "***" }}, " +
            "queryMethod=$queryMethod, updateMethod=$updateMethod, insertBatchSize=$insertBatchSize, " +
            "streamingRequestTimeout=$streamingRequestTimeout, followCrossOriginRedirects=$followCrossOriginRedirects, " +
            "maxRedirects=$maxRedirects, maxGetUrlLength=$maxGetUrlLength, maxBlankNodeComponentTriples=$maxBlankNodeComponentTriples, " +
            "maxResultRowChars=$maxResultRowChars, strictContentType=$strictContentType, malformedTerms=$malformedTerms)"

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 30_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 60_000
        const val DEFAULT_REQUEST_TIMEOUT_MINUTES: Long = 5
        const val DEFAULT_INSERT_BATCH_SIZE: Int = 5_000
        const val DEFAULT_MAX_REDIRECTS: Int = 5
        const val DEFAULT_MAX_GET_URL_LENGTH: Int = 2_000
        const val DEFAULT_MAX_BLANK_NODE_COMPONENT_TRIPLES: Int = 100_000
        const val DEFAULT_MAX_RESULT_ROW_CHARS: Int = 4 * 1024 * 1024

        /** An RFC 9110 `token`. */
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        private fun isFieldSpace(c: Char) = c == ' ' || c == '\t'

        /** RFC 9110 `field-content`: visible ASCII, `obs-text` (0x80-0xFF), space and tab; no other control character. */
        private fun isFieldValueChar(c: Char) = isFieldSpace(c) || c.code in 0x21..0x7E || c.code in 0x80..0xFF

        private fun printable(text: String) = text.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("")
        private val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade", "transfer-encoding")

        /** Headers the adapter sets on every request, with the reason a custom value is refused. */
        private val ADAPTER_HEADERS = mapOf(
            "accept" to "the adapter asks for application/sparql-results+json, the only result format it reads",
            "content-type" to "the adapter sets it to the media type of the request body it sends (see queryMethod and updateMethod)",
        )

        private val CREDENTIAL_WORDS = listOf("auth", "key", "token", "secret", "password", "credential", "session")

        /**
         * Build a configuration from provider options ([com.geoknoesis.kastor.rdf.RdfConfig.options]):
         * `location` (required), `updateLocation`, `maxResponseBytes`, `maxStreamedResponseBytes`,
         * `connectTimeoutMillis`, `readTimeoutMillis`, `requestTimeoutMillis`,
         * `streamingRequestTimeoutMillis`, `username`, `password`, `queryMethod`
         * (`POST`/`POST_FORM`/`GET`), `updateMethod` (`POST`/`POST_FORM`), `insertBatchSize`,
         * `followCrossOriginRedirects` (`true`/`false`), `maxRedirects`, `maxGetUrlLength`,
         * `maxBlankNodeComponentTriples`, `maxResultRowChars`, `strictContentType` (`true`/`false`),
         * `malformedTerms` (`FAIL`/`SKIP_ROW`), and `header.<Name>` for custom headers.
         * `maxStreamedResponseBytes`, `requestTimeoutMillis` and `streamingRequestTimeoutMillis`
         * accept `none` for unbounded.
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
            fun boolean(key: String, default: Boolean): Boolean = options[key]?.trim()?.let { raw ->
                when (raw.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Option '$key' must be true or false, got '$raw'")
                }
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
                streamingRequestTimeout = optionalLong("streamingRequestTimeoutMillis", null)?.let(Duration::ofMillis),
                followCrossOriginRedirects = boolean("followCrossOriginRedirects", defaults.followCrossOriginRedirects),
                maxRedirects = long("maxRedirects")?.let(Math::toIntExact) ?: defaults.maxRedirects,
                maxGetUrlLength = long("maxGetUrlLength")?.let(Math::toIntExact) ?: defaults.maxGetUrlLength,
                maxBlankNodeComponentTriples = long("maxBlankNodeComponentTriples")?.let(Math::toIntExact)
                    ?: defaults.maxBlankNodeComponentTriples,
                maxResultRowChars = long("maxResultRowChars")?.let(Math::toIntExact) ?: defaults.maxResultRowChars,
                strictContentType = boolean("strictContentType", defaults.strictContentType),
                malformedTerms = enum("malformedTerms", MalformedTermPolicy.values(), defaults.malformedTerms),
            )
        }

        private fun requirePositive(duration: Duration, name: String) = Durations.requirePositive(duration, name)
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
    // Directional literals are not decoded from JSON results and remote support cannot be assumed.
    supportsBaseDirection = false,
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

    val isPlainHttp: Boolean get() = url.protocol.equals("http", ignoreCase = true)

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
            // A fragment is never sent to the server, and a GET query parameter would be appended after it.
            require(uri.rawFragment == null) { "SPARQL endpoint URL must not contain a fragment ('#...'): '${redact(endpoint)}'" }
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
