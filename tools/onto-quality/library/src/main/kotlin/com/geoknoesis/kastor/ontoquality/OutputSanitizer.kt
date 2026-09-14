package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.explanation.escapeMarkdownInline
import com.geoknoesis.kastor.ontoquality.explanation.sanitizeTerminalText

/**
 * The sanitisers used by the onto-quality reports, for callers (such as the `onto-qa` CLI) that print untrusted text
 * — ontology-derived messages, exception messages, LLM output — outside a report.
 */
object OutputSanitizer {
    /**
     * Safe for a terminal: C0 / C1 control characters other than newline and tab, and Unicode bidi controls, are
     * rendered visibly as `\uXXXX`.
     */
    @JvmStatic
    fun terminal(text: String): String = sanitizeTerminalText(text)

    /**
     * Safe as one inline Markdown run: Markdown punctuation (including `@`) is backslash-escaped, line breaks become
     * spaces, URL autolinks are broken and bidi controls are rendered visibly as `\uXXXX`.
     */
    @JvmStatic
    fun markdownInline(text: String): String = escapeMarkdownInline(text)
}
