package com.geoknoesis.kastor.gen.processor.utils

import com.geoknoesis.kastor.gen.processor.internal.utils.Rfc3986
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The normal and abnormal examples of RFC 3986 §5.4. */
class Rfc3986Test {

    private val base = "http://a/b/c/d;p?q"

    private val examples = mapOf(
        "g:h" to "g:h", "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/",
        "/g" to "http://a/g", "//g" to "http://g", "?y" to "http://a/b/c/d;p?y", "g?y" to "http://a/b/c/g?y",
        "#s" to "http://a/b/c/d;p?q#s", "g#s" to "http://a/b/c/g#s", "g?y#s" to "http://a/b/c/g?y#s",
        ";x" to "http://a/b/c/;x", "g;x" to "http://a/b/c/g;x", "g;x?y#s" to "http://a/b/c/g;x?y#s",
        "" to "http://a/b/c/d;p?q", "." to "http://a/b/c/", "./" to "http://a/b/c/", ".." to "http://a/b/",
        "../" to "http://a/b/", "../g" to "http://a/b/g", "../.." to "http://a/", "../../" to "http://a/",
        "../../g" to "http://a/g",
        // Abnormal examples (§5.4.2).
        "../../../g" to "http://a/g", "../../../../g" to "http://a/g", "/./g" to "http://a/g",
        "/../g" to "http://a/g", "g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g", "g.." to "http://a/b/c/g..",
        "..g" to "http://a/b/c/..g", "./../g" to "http://a/b/g", "./g/." to "http://a/b/c/g/",
        "g/./h" to "http://a/b/c/g/h", "g/../h" to "http://a/b/c/h", "g;x=1/./y" to "http://a/b/c/g;x=1/y",
        "g;x=1/../y" to "http://a/b/c/y", "g?y/./x" to "http://a/b/c/g?y/./x", "g?y/../x" to "http://a/b/c/g?y/../x",
        "g#s/./x" to "http://a/b/c/g#s/./x", "g#s/../x" to "http://a/b/c/g#s/../x", "http:g" to "http:g",
    )

    @Test
    fun `every example of RFC 3986 section 5_4 resolves as specified`() {
        val wrong = examples.mapNotNull { (ref, expected) ->
            Rfc3986.resolve(base, ref).takeIf { it != expected }?.let { "$ref -> $it (expected $expected)" }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a base without a path resolves as rooted`() {
        assertEquals("http://ex.org/c", Rfc3986.resolve("http://ex.org", "c"))
        assertEquals("urn:x:y", Rfc3986.resolve("urn:a", "urn:x:y"))
    }
}
