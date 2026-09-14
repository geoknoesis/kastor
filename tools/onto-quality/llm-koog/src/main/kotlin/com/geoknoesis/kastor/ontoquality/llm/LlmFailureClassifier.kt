package com.geoknoesis.kastor.ontoquality.llm

import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration

/**
 * How an LLM request failure should be handled.
 *
 * @param retryable true for transient failures (timeouts, HTTP 408 / 429 / 5xx, connection errors); false for client
 *   errors, exhausted quotas (`insufficient_quota`), TLS / certificate failures and unknown errors.
 * @param signature short, stable description used in failure reasons and to detect repeated identical failures.
 * @param retryAfter server-requested delay before retrying, when the provider error carries one.
 */
internal data class LlmFailureKind(
    val retryable: Boolean,
    val signature: String,
    val retryAfter: Duration? = null,
)

/**
 * Classifies LLM transport failures without depending on provider client internals.
 *
 * Koog 0.8 surfaces HTTP errors as `KoogHttpClientException` (with a `statusCode` property and an `errorBody`) or
 * as `LLMClientException` whose message contains `Status code: NNN`; the message fallback also understands HTTP
 * status lines (`HTTP/1.1 503`) and ktor's `Server error(POST https://…: 503 Service Unavailable…)`. Everything is
 * inspected along the cause chain. Response headers are not exposed, so `Retry-After` is honoured only when it appears
 * in the message or error body.
 *
 * Not retried although they may arrive as HTTP 429 or as I/O exceptions: OpenAI `insufficient_quota` (the account has
 * no credit; waiting does not help) and TLS / certificate failures (`SSLException`, `CertificateException`,
 * `CertPathValidatorException`), which a retry cannot fix. Only known timeout and connection exception types count
 * as transport failures — not any class whose name happens to contain "Timeout".
 */
internal object LlmFailureClassifier {
    private const val MAX_CAUSE_DEPTH = 16
    private const val MAX_RETRY_AFTER_SECONDS = 3600.0
    private val STATUS_IN_MESSAGE = Regex("""(?i)\b(?:status(?:\s*code)?|http)\s*[:=]?\s*(\d{3})\b""")
    private val HTTP_STATUS_LINE = Regex("""(?i)\bHTTP/\d(?:\.\d)?\s+(\d{3})\b""")

    /** ktor `ResponseException` messages: `Server error(POST https://host:443/path: 503 Service Unavailable. Text: …)`. */
    private val KTOR_RESPONSE_ERROR = Regex("""(?i)\b(?:server|client|redirect) error\(\S+\s+\S*?:\s+(\d{3})\b""")
    private val INSUFFICIENT_QUOTA = Regex("""(?i)\binsufficient_quota\b""")

    /** Timeout / connection exception types (and subtypes) that are transport failures without being [IOException]s. */
    private val TRANSPORT_EXCEPTION_TYPES =
        setOf(
            "java.util.concurrent.TimeoutException",
            "io.ktor.client.plugins.HttpRequestTimeoutException",
            "io.ktor.client.network.sockets.ConnectTimeoutException",
            "io.ktor.client.network.sockets.SocketTimeoutException",
        )
    private val RETRY_AFTER = Regex("""(?i)retry-after["']?\s*[:=]?\s*(\d+(?:\.\d+)?)""")
    private val TRY_AGAIN_IN = Regex("""(?i)try again in\s+(\d+(?:\.\d+)?)\s*(ms|s)\b""")

    fun classify(failure: Throwable): LlmFailureKind {
        if (failure is TimeoutCancellationException) return LlmFailureKind(retryable = true, signature = "timeout")
        val chain = generateSequence(failure) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        val retryAfter = chain.firstNotNullOfOrNull(::retryAfterOf)
        val status = chain.firstNotNullOfOrNull(::statusOf)
        if (chain.any(::isInsufficientQuota)) {
            return LlmFailureKind(retryable = false, signature = if (status != null) "HTTP $status insufficient_quota" else "insufficient_quota")
        }
        chain.firstOrNull(::isTlsFailure)?.let {
            return LlmFailureKind(retryable = false, signature = "TLS/certificate error (${it.javaClass.simpleName})")
        }
        if (status != null) {
            val retryable = status == 408 || status == 429 || status in 500..599
            return LlmFailureKind(retryable, "HTTP $status", retryAfter.takeIf { retryable })
        }
        chain.firstOrNull(::isTransportFailure)?.let {
            return LlmFailureKind(retryable = true, signature = "connection error (${it.javaClass.simpleName})", retryAfter = retryAfter)
        }
        val firstLine = failure.message?.lineSequence()?.firstOrNull()?.take(200)
        return LlmFailureKind(retryable = false, signature = "${failure.javaClass.simpleName}: $firstLine")
    }

    private fun isTransportFailure(t: Throwable): Boolean =
        t is IOException || t is UnresolvedAddressException || t is TimeoutCancellationException ||
            generateSequence<Class<*>>(t.javaClass) { it.superclass }.any { it.name in TRANSPORT_EXCEPTION_TYPES }

    private fun isTlsFailure(t: Throwable): Boolean =
        t is javax.net.ssl.SSLException || t is java.security.cert.CertificateException ||
            t is java.security.cert.CertPathValidatorException || t is java.security.cert.CertPathBuilderException

    private fun isInsufficientQuota(t: Throwable): Boolean =
        listOfNotNull(t.message, property(t, "getErrorBody") as? String).any { INSUFFICIENT_QUOTA.containsMatchIn(it) }

    private fun statusOf(t: Throwable): Int? {
        (property(t, "getStatusCode") as? Number)?.toInt()?.takeIf { it in 100..599 }?.let { return it }
        val message = t.message ?: return null
        return sequenceOf(STATUS_IN_MESSAGE, HTTP_STATUS_LINE, KTOR_RESPONSE_ERROR)
            .firstNotNullOfOrNull { it.find(message)?.groupValues?.get(1)?.toInt()?.takeIf { code -> code in 100..599 } }
    }

    private fun retryAfterOf(t: Throwable): Duration? {
        for (text in listOfNotNull(t.message, property(t, "getErrorBody") as? String)) {
            RETRY_AFTER.find(text)?.let { return seconds(it.groupValues[1].toDouble()) }
            TRY_AGAIN_IN.find(text)?.let {
                val value = it.groupValues[1].toDouble()
                return seconds(if (it.groupValues[2].equals("ms", ignoreCase = true)) value / 1000.0 else value)
            }
        }
        return null
    }

    private fun seconds(value: Double): Duration = Duration.ofMillis((value.coerceIn(0.0, MAX_RETRY_AFTER_SECONDS) * 1000).toLong())

    private fun property(t: Throwable, getter: String): Any? =
        try {
            val method = t.javaClass.getMethod(getter)
            if (method.parameterCount != 0) {
                null
            } else {
                runCatching { method.isAccessible = true }
                method.invoke(t)
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}
