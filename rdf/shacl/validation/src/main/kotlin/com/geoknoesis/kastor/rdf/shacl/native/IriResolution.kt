package com.geoknoesis.kastor.rdf.shacl.native

/** RFC 3986 Appendix B: the scheme, authority, path, query and fragment of an IRI reference. */
private val IRI_REFERENCE_PARTS = Regex("""^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$""", RegexOption.DOT_MATCHES_ALL)

/** Whether [iri] has a scheme, so that it needs no base. */
internal fun isAbsoluteIri(iri: String): Boolean = IRI_REFERENCE_PARTS.matchEntire(iri)?.groups?.get(1) != null

/**
 * [reference] resolved against the absolute [base] (RFC 3986 5.2.2, strict parser). An absolute reference without
 * dot segments is its own resolution and is returned as it is. [tick] is called per path segment that
 * [removeDotSegments] processes, so the work stays linear in the length of the IRIs and on the budget.
 */
internal fun resolveIri(base: String, reference: String, tick: () -> Unit = {}): String {
    val r = IRI_REFERENCE_PARTS.matchEntire(reference)!!.groups
    if (r[1] != null && !hasDotSegment(r[3]!!.value)) return reference
    val b = IRI_REFERENCE_PARTS.matchEntire(base)!!.groups
    val basePath = b[3]!!.value
    val refPath = r[3]!!.value
    val scheme: String?
    val authority: String?
    val path: String
    val query: String?
    when {
        r[1] != null -> {
            scheme = r[1]!!.value
            authority = r[2]?.value
            path = removeDotSegments(refPath, tick)
            query = r[4]?.value
        }
        r[2] != null -> {
            scheme = b[1]?.value
            authority = r[2]!!.value
            path = removeDotSegments(refPath, tick)
            query = r[4]?.value
        }
        refPath.isEmpty() -> {
            scheme = b[1]?.value
            authority = b[2]?.value
            path = basePath
            query = r[4]?.value ?: b[4]?.value
        }
        else -> {
            scheme = b[1]?.value
            authority = b[2]?.value
            // RFC 3986 5.2.3 (merge).
            val merged = when {
                refPath.startsWith("/") -> refPath
                b[2] != null && basePath.isEmpty() -> "/$refPath"
                else -> basePath.substring(0, basePath.lastIndexOf('/') + 1) + refPath
            }
            path = removeDotSegments(merged, tick)
            query = r[4]?.value
        }
    }
    return buildString {
        if (scheme != null) append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        append(path)
        if (query != null) append('?').append(query)
        r[5]?.let { append('#').append(it.value) }
    }
}

/** Whether [path] has a `.` or `..` segment, which [removeDotSegments] would remove. */
private fun hasDotSegment(path: String): Boolean {
    var start = 0
    while (start <= path.length) {
        val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
        val length = end - start
        if ((length == 1 && path[start] == '.') || (length == 2 && path[start] == '.' && path[start + 1] == '.')) return true
        start = end + 1
    }
    return false
}

/**
 * RFC 3986 5.2.4, in time linear in the length of [path]: the input is read through an index instead of being cut
 * into substrings, and the output keeps a stack of where each of its segments starts, so that removing the last one
 * (rule C) is a truncation. [tick] is called once per step (one segment or dot segment).
 */
private fun removeDotSegments(path: String, tick: () -> Unit = {}): String {
    val n = path.length
    val out = StringBuilder(n)
    // Where each segment of [out] starts; a segment starts with its '/' unless it is the first one.
    val starts = IntArray(n + 1)
    var depth = 0
    fun rest(i: Int, prefix: String) = path.startsWith(prefix, i)
    fun restIs(i: Int, whole: String) = n - i == whole.length && path.startsWith(whole, i)
    fun popSegment() {
        if (depth > 0) out.setLength(starts[--depth]) else out.setLength(0)
    }
    fun push(text: CharSequence, from: Int, to: Int) {
        starts[depth++] = out.length
        out.append(text, from, to)
    }
    var i = 0
    while (i < n) {
        tick()
        when {
            // A: a leading "../" or "./" is removed.
            rest(i, "../") -> i += 3
            rest(i, "./") -> i += 2
            // B: "/./" becomes "/"; a final "/." becomes "/", which E then moves to the output.
            rest(i, "/./") -> i += 2
            restIs(i, "/.") -> {
                push("/", 0, 1)
                i = n
            }
            // C: "/../" becomes "/" and the last output segment goes; so for a final "/..".
            rest(i, "/../") -> {
                i += 3
                popSegment()
            }
            restIs(i, "/..") -> {
                popSegment()
                push("/", 0, 1)
                i = n
            }
            // D: a lone "." or "..".
            restIs(i, ".") || restIs(i, "..") -> i = n
            // E: the first segment, with its leading '/', moves to the output.
            else -> {
                val end = path.indexOf('/', i + 1).let { if (it < 0) n else it }
                push(path, i, end)
                i = end
            }
        }
    }
    return out.toString()
}
