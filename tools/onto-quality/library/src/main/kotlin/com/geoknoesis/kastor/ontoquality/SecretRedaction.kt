package com.geoknoesis.kastor.ontoquality

/**
 * Replaces secrets (API keys, URL credentials) by `***` in text that is printed or stored: failure reasons, error
 * messages, stack traces.
 *
 * - The credentials of every URL (`scheme://user:password@host`) are replaced, whatever they are.
 * - A secret of at least [MIN_SUBSTRING_SECRET_LENGTH] characters is replaced wherever it occurs.
 * - A shorter secret would mangle unrelated text (with the key `1`, "HTTP 401" would become "HTTP 40***" and every
 *   count would change), so it is replaced only where it stands as a whole token in a **credential position**: after
 *   a credential label (`Authorization`, `Bearer`, `x-api-key`, `api key`, `key`, `token`, `secret`, `password`),
 *   optionally separated by `:` or `=` and quotes, as in `Authorization: Bearer k3y`, `?key=k3y`, `"api_key":"k3y"`
 *   or `API key provided: k3y`. URL user information is covered by the first rule.
 *
 * Pass only the secrets of the run at hand (the key of the provider in use), not every key of the environment.
 */
object SecretRedaction {
    /** Shortest secret that is replaced as a substring; shorter ones only in credential positions. */
    const val MIN_SUBSTRING_SECRET_LENGTH: Int = 8

    const val MASK: String = "***"

    /** `scheme://user:password@host` in running text; the credentials are group 2. */
    private val URL_CREDENTIALS = Regex("([A-Za-z][A-Za-z0-9+.\\-]*://)([^/\\s?#]*)@")

    private const val CREDENTIAL_LABEL =
        "(?i)(\\b(?:authorization|bearer|basic|x-api-key|api[-_ ]?key|apikey|key|token|secret|password|passwd|pwd)\\b" +
            "[\"']?\\s*(?:provided)?\\s*[:=]?\\s*(?:(?:bearer|basic)\\s+)?[\"']?)"

    private const val TOKEN_END = "(?![\\p{L}\\p{N}_\\-])"

    /** Whether [secret] is too short to be replaced as a substring. */
    @JvmStatic
    fun isShort(secret: String): Boolean = secret.length < MIN_SUBSTRING_SECRET_LENGTH

    /** [text] with the user name and password of every URL replaced by `***` (the part between `://` and `@`). */
    @JvmStatic
    fun redactUrlCredentials(text: String): String =
        if (!text.contains("://")) text else URL_CREDENTIALS.replace(text) { "${it.groupValues[1]}$MASK@" }

    /** The credentials of [url] (`user:password`, and the password alone), or nothing when it has none. */
    @JvmStatic
    fun urlCredentials(url: String?): List<String> {
        val userInfo = url?.let { URL_CREDENTIALS.find(it) }?.groupValues?.get(2)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(userInfo, userInfo.substringAfter(':', "")).filter { it.isNotBlank() }
    }

    /**
     * [text] without URL credentials and without [secrets] (see the class description for short secrets). Longer
     * secrets are replaced first, so a secret that contains another is replaced as a whole.
     */
    @JvmStatic
    fun redact(text: String, secrets: Collection<String>): String {
        var out = redactUrlCredentials(text)
        for (secret in secrets.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }) {
            if (!out.contains(secret)) continue
            out =
                if (isShort(secret)) {
                    Regex(CREDENTIAL_LABEL + Regex.escape(secret) + TOKEN_END).replace(out) { "${it.groupValues[1]}$MASK" }
                } else {
                    out.replace(secret, MASK)
                }
        }
        return out
    }
}
