package com.geoknoesis.kastor.gen.processor.internal.utils

/**
 * Reference resolution of RFC 3986 §5.2 (strict mode), with `remove_dot_segments` (§5.2.4). `java.net.URI.resolve`
 * follows RFC 2396 instead: it drops the base's last segment for a query-only reference (`?y` against
 * `http://a/b/c/d;p?q` gives `http://a/b/c/?y`, not `http://a/b/c/d;p?y`) and keeps `..` segments above the root.
 */
internal object Rfc3986 {

    /** RFC 3986 Appendix B: scheme, authority, path, query and fragment of a URI reference. */
    private val PARTS = Regex("^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:[?]([^#]*))?(?:#(.*))?$")

    private class Parts(val scheme: String?, val authority: String?, val path: String, val query: String?, val fragment: String?)

    private fun split(value: String): Parts {
        val m = PARTS.matchEntire(value)!!.groups
        return Parts(m[1]?.value, m[2]?.value, m[3]?.value.orEmpty(), m[4]?.value, m[5]?.value)
    }

    /** Whether [value] has a scheme, i.e. is an absolute URI (possibly with a fragment). */
    fun hasScheme(value: String): Boolean = split(value).scheme != null

    /** [reference] resolved against the absolute URI [base]. */
    fun resolve(base: String, reference: String): String {
        val r = split(reference)
        val b = split(base)
        require(b.scheme != null) { "base <$base> is not absolute" }
        val scheme: String
        val authority: String?
        val path: String
        val query: String?
        when {
            r.scheme != null -> { scheme = r.scheme; authority = r.authority; path = removeDotSegments(r.path); query = r.query }
            r.authority != null -> { scheme = b.scheme; authority = r.authority; path = removeDotSegments(r.path); query = r.query }
            r.path.isEmpty() -> { scheme = b.scheme; authority = b.authority; path = b.path; query = r.query ?: b.query }
            else -> {
                scheme = b.scheme
                authority = b.authority
                path = removeDotSegments(if (r.path.startsWith("/")) r.path else merge(b, r.path))
                query = r.query
            }
        }
        return buildString {
            append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (r.fragment != null) append('#').append(r.fragment)
        }
    }

    /** §5.2.3. */
    private fun merge(base: Parts, path: String): String =
        if (base.authority != null && base.path.isEmpty()) "/$path"
        else base.path.substringBeforeLast('/', "").let { if ('/' in base.path) "$it/$path" else path }

    /** §5.2.4, over segments: linear in the length of [path]. */
    fun removeDotSegments(path: String): String {
        if (path.isEmpty()) return path
        val absolute = path.startsWith("/")
        val segments = (if (absolute) path.substring(1) else path).split('/')
        val out = ArrayList<String>()
        segments.forEachIndexed { i, segment ->
            val last = i == segments.lastIndex
            when (segment) {
                "." -> if (last) out += ""
                ".." -> {
                    if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                    if (last) out += ""
                }
                else -> out += segment
            }
        }
        return (if (absolute) "/" else "") + out.joinToString("/")
    }
}
