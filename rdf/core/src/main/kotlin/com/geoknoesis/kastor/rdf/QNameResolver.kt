package com.geoknoesis.kastor.rdf

/**
 * The prefix mappings of a DSL: the built-in defaults, plus what the user declares. It remembers which prefixes were
 * **declared** - put, by any of the map's mutators, after construction - because a declared prefix always wins over
 * a URI scheme of the same name, while a built-in default never captures a URI of a well-known scheme
 * (see [QNameResolver.resolve]). Removing a prefix removes its declaration.
 */
internal class PrefixMappings(defaults: Map<String, String>) : AbstractMutableMap<String, String>() {
    private val mappings = LinkedHashMap(defaults)
    private val declared = HashSet<String>()

    /** True if the user declared [prefix] (also when the declaration repeats a built-in default). */
    fun isDeclared(prefix: String): Boolean = prefix in declared

    override val size: Int get() = mappings.size
    override fun containsKey(key: String): Boolean = mappings.containsKey(key)
    override fun get(key: String): String? = mappings[key]

    override fun put(key: String, value: String): String? {
        declared.add(key)
        return mappings.put(key, value)
    }

    override fun remove(key: String): String? {
        declared.remove(key)
        return mappings.remove(key)
    }

    override fun clear() {
        declared.clear()
        mappings.clear()
    }

    /** A view whose removals and `setValue` go through this map, so that the declarations stay in step. */
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, String>>() {
            override val size: Int get() = mappings.size
            override fun add(element: MutableMap.MutableEntry<String, String>): Boolean {
                val changed = mappings[element.key] != element.value
                put(element.key, element.value)
                return changed
            }
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> {
                val inner = mappings.entries.iterator()
                return object : MutableIterator<MutableMap.MutableEntry<String, String>> {
                    private var current: String? = null
                    override fun hasNext(): Boolean = inner.hasNext()
                    override fun next(): MutableMap.MutableEntry<String, String> {
                        val entry = inner.next()
                        current = entry.key
                        return object : MutableMap.MutableEntry<String, String> {
                            override val key: String get() = entry.key
                            override val value: String get() = entry.value
                            override fun setValue(newValue: String): String {
                                declared.add(entry.key)
                                return entry.setValue(newValue)
                            }
                        }
                    }
                    override fun remove() {
                        inner.remove()
                        current?.let(declared::remove)
                    }
                }
            }
        }
}

/**
 * Utility for resolving QNames (qualified names) to full IRIs using prefix mappings.
 */
internal object QNameResolver {
    /**
     * URI schemes that are taken for what they are when no prefix of that name was declared: `urn:isbn:...`,
     * `mailto:a@b`, `geo:37.7,-122.4` are absolute IRIs, not QNames with an unknown prefix.
     */
    private val WELL_KNOWN_SCHEMES = setOf(
        "http", "https", "urn", "data", "file", "mailto", "tel", "geo", "ftp", "ftps", "sftp", "ws", "wss",
        "tag", "did", "info", "jar", "cid", "mid", "ldap", "news", "sms", "blob", "about",
    )

    /**
     * Resolves a QName or IRI string to a full IRI using the provided prefix mappings.
     *
     * - `scheme://...` is always an absolute IRI.
     * - A prefix the user **declared** always wins: `prefix("data", "http://ex/data/")` makes `data:item1`
     *   `http://ex/data/item1`, whatever `data:` means as a URI scheme. With a plain [Map], every mapping counts as
     *   declared; with [PrefixMappings], the built-in defaults do not.
     * - An undeclared prefix that is a well-known URI scheme ([WELL_KNOWN_SCHEMES]) makes an absolute IRI:
     *   `urn:isbn:0451450523`, `mailto:a@example.org`, `tel:+1-201-555-0123`, `data:text/plain,hi`, `file:/x`.
     * - A built-in default prefix that is also a well-known scheme (`geo`: GeoSPARQL, and the `geo:` URI scheme of
     *   RFC 5870) expands only what is shaped like a local name - it starts with a letter or `_` and continues with
     *   letters, digits, `_`, `-` and `.` (`geo:asWKT`); anything else is the URI it looks like (`geo:37.7,-122.4`).
     * - Any other prefix must be mapped: built-in defaults expand, an unknown prefix fails.
     *
     * @param iriOrQName The QName (e.g., "foaf:name") or full IRI
     * @param prefixMappings Map of prefix names to namespace URIs
     * @return The full IRI
     * @throws IllegalArgumentException if the QName cannot be resolved
     */
    fun resolve(iriOrQName: String, prefixMappings: Map<String, String>): String {
        val colon = iriOrQName.indexOf(':')
        // No colon, an empty prefix or an empty local part: not a QName, returned as it is.
        if (colon <= 0 || iriOrQName.endsWith(":")) return iriOrQName
        // scheme://authority is a URL, whatever the prefixes say.
        if (iriOrQName.startsWith("//", colon + 1)) return iriOrQName
        val prefix = iriOrQName.substring(0, colon)
        val localName = iriOrQName.substring(colon + 1)
        val namespace = prefixMappings[prefix]
        val declared = namespace != null && (prefixMappings !is PrefixMappings || prefixMappings.isDeclared(prefix))
        if (!declared && prefix.lowercase() in WELL_KNOWN_SCHEMES) {
            // A built-in default never captures a URI of that scheme; it still serves the names of its vocabulary.
            if (namespace == null || !isLocalNameShaped(localName)) return iriOrQName
        }
        if (namespace == null) throw IllegalArgumentException("Unknown prefix: '$prefix' in QName: '$iriOrQName'")
        val resolved = namespace + localName
        RdfDebug.logPrefixExpansion(iriOrQName, resolved, prefixMappings)
        return resolved
    }

    /** True for what a vocabulary term looks like and a URI of a well-known scheme does not: `asWKT`, `has_part`. */
    private fun isLocalNameShaped(localName: String): Boolean {
        val first = localName[0]
        if (!(first.isLetter() || first == '_')) return false
        return localName.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}
