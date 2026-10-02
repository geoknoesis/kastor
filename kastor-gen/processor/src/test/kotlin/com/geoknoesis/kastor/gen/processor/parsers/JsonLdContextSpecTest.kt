package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContainer
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.internal.parsers.JsonLdContextParser
import com.geoknoesis.kastor.rdf.Iri
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contexts as ordinary JSON-LD documents write them, taken from the examples of the JSON-LD 1.1 recommendation
 * (https://www.w3.org/TR/json-ld11/): keyword aliases, type coercion keywords, array-valued containers, reverse
 * properties and scoped contexts.
 */
class JsonLdContextSpecTest {

    private val logger = object : KSPLogger {
        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) {}
        override fun warn(message: String, symbol: KSNode?) {}
        override fun error(message: String, symbol: KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    private fun parse(context: String): JsonLdContext = JsonLdContextParser(logger).parseContextContent("""{ "@context": $context }""")

    private fun rejected(context: String): String =
        assertThrows(IllegalArgumentException::class.java) { parse(context) }.message!!

    @Test
    fun `keyword aliases are aliases, not terms - without a vocabulary`() {
        // JSON-LD 1.1, 4.1.6 "Aliasing keywords" (example 21 style).
        val context = parse(
            """
            {
              "url": "@id",
              "a": "@type",
              "name": "http://xmlns.com/foaf/0.1/name"
            }
            """,
        )
        assertEquals(mapOf("url" to "@id", "a" to "@type"), context.keywordAliases)
        assertEquals(mapOf("name" to Iri("http://xmlns.com/foaf/0.1/name")), context.typeMappings)
        assertTrue(context.propertyMappings.isEmpty())
    }

    @Test
    fun `keyword aliases do not become type mappings under a vocabulary`() {
        val context = parse(
            """
            {
              "@vocab": "http://schema.org/",
              "id": "@id",
              "type": "@type",
              "graph": { "@id": "@graph", "@container": "@set" }
            }
            """,
        )
        assertEquals(mapOf("id" to "@id", "type" to "@type", "graph" to "@graph"), context.keywordAliases)
        assertTrue(context.typeMappings.isEmpty(), "an alias of @id is not the class http://schema.org/@id")
        assertTrue(context.propertyMappings.isEmpty())
    }

    @Test
    fun `type coercion keywords are keywords, not vocabulary terms`() {
        // JSON-LD 1.1, 4.2.3 "Type coercion" (the "relative-iri" / "vocabulary" example) and 4.2.2 "JSON literals".
        val context = parse(
            """
            {
              "@base": "http://example1.com/",
              "@vocab": "http://example2.com/",
              "knows": { "@type": "@vocab" },
              "homepage": { "@id": "http://xmlns.com/foaf/0.1/homepage", "@type": "@id" },
              "e": { "@id": "http://example.com/vocab/json", "@type": "@json" },
              "untyped": { "@id": "http://example.com/vocab/untyped", "@type": "@none" },
              "age": { "@id": "http://xmlns.com/foaf/0.1/age", "@type": "http://www.w3.org/2001/XMLSchema#integer" }
            }
            """,
        )
        assertEquals(JsonLdType.Vocab, context.propertyMappings.getValue("knows").type)
        assertEquals(Iri("http://example2.com/knows"), context.propertyMappings.getValue("knows").id, "no @id: the term itself, in the vocabulary")
        assertEquals(JsonLdType.Id, context.propertyMappings.getValue("homepage").type)
        assertEquals(JsonLdType.Json, context.propertyMappings.getValue("e").type)
        assertEquals(JsonLdType.None, context.propertyMappings.getValue("untyped").type)
        assertEquals(
            JsonLdType.Iri(Iri("http://www.w3.org/2001/XMLSchema#integer")),
            context.propertyMappings.getValue("age").type,
        )
    }

    @Test
    fun `type coercion keywords work without a vocabulary`() {
        val context = parse("""{ "knows": { "@id": "http://xmlns.com/foaf/0.1/knows", "@type": "@vocab" } }""")
        assertEquals(JsonLdType.Vocab, context.propertyMappings.getValue("knows").type)
    }

    @Test
    fun `a container may be an array of keywords`() {
        // JSON-LD 1.1, 4.6 "Indexed values": language maps, id maps, graph containers, each optionally with @set.
        val context = parse(
            """
            {
              "ex": "http://example.com/vocab/",
              "occupation": { "@id": "ex:occupation", "@container": ["@set", "@language"] },
              "label": { "@id": "ex:label", "@container": "@language" },
              "post": { "@id": "ex:post", "@container": ["@id", "@set"] },
              "claim": { "@id": "ex:claim", "@container": ["@graph", "@index"] },
              "tags": { "@id": "ex:tags", "@container": ["@set"] },
              "affiliation": { "@id": "ex:affiliation", "@container": "@type" }
            }
            """,
        )
        val occupation = context.propertyMappings.getValue("occupation")
        assertEquals(listOf(JsonLdContainer.Set, JsonLdContainer.Language), occupation.containers)
        assertEquals(JsonLdContainer.Language, occupation.container, "@set only says 'always an array'")
        assertEquals(JsonLdContainer.Language, context.propertyMappings.getValue("label").container)
        assertEquals(listOf(JsonLdContainer.Language), context.propertyMappings.getValue("label").containers)
        assertEquals(JsonLdContainer.Id, context.propertyMappings.getValue("post").container)
        assertEquals(listOf(JsonLdContainer.Graph, JsonLdContainer.Index), context.propertyMappings.getValue("claim").containers)
        assertEquals(JsonLdContainer.Graph, context.propertyMappings.getValue("claim").container)
        assertEquals(JsonLdContainer.Set, context.propertyMappings.getValue("tags").container)
        assertEquals(JsonLdContainer.Type, context.propertyMappings.getValue("affiliation").container)
    }

    @Test
    fun `a reverse property is recorded with the property it reverses`() {
        // JSON-LD 1.1, 4.8 "Reverse properties".
        val context = parse(
            """
            {
              "name": "http://example.com/vocab#name",
              "children": { "@reverse": "http://example.com/vocab#parent" },
              "parent": { "@id": "http://example.com/vocab#parent", "@type": "@id" }
            }
            """,
        )
        val children = context.propertyMappings.getValue("children")
        assertEquals(Iri("http://example.com/vocab#parent"), children.id)
        assertTrue(children.reverse)
        assertFalse(context.propertyMappings.getValue("parent").reverse)
    }

    @Test
    fun `scoped contexts are resolved against the context that declares them`() {
        // JSON-LD 1.1, 4.1.8 "Scoped contexts": property-scoped ("interest") and type-scoped ("Person").
        val context = parse(
            """
            {
              "@version": 1.1,
              "name": "http://schema.org/name",
              "xsd": "http://www.w3.org/2001/XMLSchema#",
              "interest": {
                "@id": "http://xmlns.com/foaf/0.1/interest",
                "@context": { "@vocab": "http://xmlns.com/foaf/0.1/", "since": { "@type": "xsd:date" } }
              },
              "Person": {
                "@id": "http://schema.org/Person",
                "@context": { "address": { "@id": "http://schema.org/address", "@container": ["@set"] } }
              }
            }
            """,
        )
        val interest = context.propertyMappings.getValue("interest")
        assertEquals(Iri("http://xmlns.com/foaf/0.1/interest"), interest.id)
        val scoped = interest.scopedContext!!
        assertEquals(Iri("http://xmlns.com/foaf/0.1/"), scoped.vocabIri)
        assertEquals(Iri("http://xmlns.com/foaf/0.1/since"), scoped.propertyMappings.getValue("since").id)
        assertEquals(
            JsonLdType.Iri(Iri("http://www.w3.org/2001/XMLSchema#date")),
            scoped.propertyMappings.getValue("since").type,
            "the prefix of the outer context is known inside",
        )
        assertNull(context.vocabIri, "the vocabulary of a scoped context does not leak out")
        assertFalse("since" in context.propertyMappings)
        val person = context.propertyMappings.getValue("Person")
        assertEquals(JsonLdContainer.Set, person.scopedContext!!.propertyMappings.getValue("address").container)
    }

    @Test
    fun `terms may refer to terms and prefixes defined later, and null removes a term`() {
        val context = parse(
            """
            [
              { "label": "http://www.w3.org/2000/01/rdf-schema#label" },
              {
                "homepage": { "@id": "foaf:homepage", "@type": "@id" },
                "foaf": "http://xmlns.com/foaf/0.1/",
                "label": null,
                "foaf:age": { "@type": "http://www.w3.org/2001/XMLSchema#integer" }
              }
            ]
            """,
        )
        assertEquals(Iri("http://xmlns.com/foaf/0.1/homepage"), context.propertyMappings.getValue("homepage").id)
        assertEquals(Iri("http://xmlns.com/foaf/0.1/age"), context.propertyMappings.getValue("foaf:age").id, "a compact IRI is its own @id")
        assertFalse("label" in context.typeMappings, "null removes the term defined by an earlier context")
        assertEquals("http://xmlns.com/foaf/0.1/", context.prefixes["foaf"])

        val referring = parse(
            """
            {
              "friend": { "@id": "knows", "@type": "@id" },
              "knows": "foaf:knows",
              "foaf": "http://xmlns.com/foaf/0.1/"
            }
            """,
        )
        assertEquals(Iri("http://xmlns.com/foaf/0.1/knows"), referring.propertyMappings.getValue("friend").id, "@id may name another term")
    }

    @Test
    fun `an expanded term definition can declare a prefix and an alias with a container`() {
        val context = parse(
            """
            {
              "compact-iris": { "@id": "http://example.com/compact-iris-", "@prefix": true },
              "property": "compact-iris:are-considered",
              "type": { "@id": "@type", "@container": "@set" }
            }
            """,
        )
        assertEquals("http://example.com/compact-iris-", context.prefixes["compact-iris"])
        assertEquals(Iri("http://example.com/compact-iris-are-considered"), context.typeMappings["property"])
        assertEquals(mapOf("type" to "@type"), context.keywordAliases)
    }

    @Test
    fun `constructs that cannot be honoured are rejected with a message that names the term`() {
        assertEquals(
            "JSON-LD context: remote context \"https://schema.org/\" is not supported: put its term definitions in the context file",
            rejected("""[ "https://schema.org/", { "name": "http://schema.org/name" } ]"""),
        )
        assertEquals(
            "JSON-LD context: term \"children\": @reverse cannot be combined with @id",
            rejected("""{ "children": { "@reverse": "http://example.com/parent", "@id": "http://example.com/child" } }"""),
        )
        assertEquals(
            "JSON-LD context: term \"name\": @container must be a keyword or an array of keywords, got 5",
            rejected("""{ "name": { "@id": "http://example.com/name", "@container": 5 } }"""),
        )
        assertEquals(
            "JSON-LD context: term \"name\": unsupported keyword @foo in the term definition",
            rejected("""{ "name": { "@id": "http://example.com/name", "@foo": true } }"""),
        )
        assertEquals(
            "JSON-LD context: term \"name\": a term definition must be a string, an object or null, got 12",
            rejected("""{ "name": 12 }"""),
        )
        assertEquals(
            "JSON-LD context: @import is not supported: put the term definitions of \"other.jsonld\" in the context file",
            rejected("""{ "@import": "other.jsonld" }"""),
        )
        assertEquals(
            "JSON-LD context: term \"name\": cannot expand \"name\": it has no prefix and the context has no @vocab or @base",
            rejected("""{ "name": { "@type": "@id" } }"""),
        )
        assertEquals(
            "JSON-LD context: term \"a\": cyclic definition (a -> b -> a)",
            rejected("""{ "a": { "@id": "b" }, "b": { "@id": "a" } }"""),
        )
    }
}
