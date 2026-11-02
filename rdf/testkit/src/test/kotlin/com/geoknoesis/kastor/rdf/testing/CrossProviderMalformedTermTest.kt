package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.fail

/**
 * A malformed IRI or language tag is never dropped silently: in every format, on both providers, through every parse
 * API, a document of one statement with such a term either fails with [RdfFormatException] or keeps that statement.
 * (Jena's parsers report some malformed IRIs and language tags as warnings and skip the statement.)
 */
class CrossProviderMalformedTermTest {
    private class Term(val name: String, val iri: String? = null, val language: String? = null)

    private val terms = listOf(
        Term("IRI with a space", iri = "http://ex/a b"),
        Term("IRI a:", iri = "a:"),
        Term("IRI http://", iri = "http://"),
        Term("IRI with an empty port", iri = "http://ex:/s"),
        Term("IRI with a bad percent escape", iri = "http://ex/%zz"),
        Term("IRI with a curly brace", iri = "http://ex/{x}"),
        Term("IRI with a backslash", iri = "http://ex/a\\\\b"),
        Term("IRI with a caret", iri = "http://ex/a^b"),
        Term("IRI with two fragments", iri = "http://ex/a#b#c"),
        Term("language subtag longer than 8", language = "en-abcdefghij"),
        Term("language with a space", language = "not a tag!"),
        Term("language with an underscore", language = "en_US"),
        Term("language starting with a digit", language = "1en"),
        Term("empty language", language = ""),
    )

    private val S = "http://ex/s"
    private val P = "http://ex/p"
    private val G = "http://ex/g"

    /** The document of one statement in [format] with [term] as its subject IRI, or its language tag. */
    private fun document(format: String, term: Term): String {
        val subject = term.iri ?: S
        val literal = term.language?.let { "\"v\"@$it" } ?: "\"v\""
        return when (format) {
            "TURTLE", "N-TRIPLES" -> "<$subject> <$P> $literal .\n"
            "TRIG" -> "<$G> { <$subject> <$P> $literal . }\n"
            "N-QUADS" -> "<$subject> <$P> $literal <$G> .\n"
            "RDF/XML" -> """<?xml version="1.0"?>
                |<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="http://ex/">
                |<rdf:Description rdf:about="${xml(subject)}"><ex:p${term.language?.let { " xml:lang=\"${xml(it)}\"" } ?: ""}>v</ex:p></rdf:Description>
                |</rdf:RDF>
                |""".trimMargin()
            else -> {
                val value = term.language?.let { """{"@value": "v", "@language": "${json(it)}"}""" } ?: "\"v\""
                """{"@id": "${json(subject)}", "$P": $value}"""
            }
        }
    }

    private fun xml(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    private fun json(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private val formats = listOf("TURTLE", "N-TRIPLES", "RDF/XML", "JSON-LD", "TRIG", "N-QUADS")

    private sealed interface Result
    private data class Parsed(val triples: Int) : Result
    private data class Refused(val message: String) : Result

    private fun attempt(parse: () -> Int): Result = try {
        Parsed(parse())
    } catch (e: RdfFormatException) {
        Refused(e.message.orEmpty())
    }

    private fun results(provider: RdfProvider, repository: () -> RdfRepository, format: String, doc: String): Map<String, Result> {
        val dataset = "parseDataset" to attempt {
            repository().use { repo ->
                provider.parseDataset(repo, doc.byteInputStream(), format, null)
                repo.defaultGraph.size() + repo.listGraphs().sumOf { repo.getGraph(it).size() }
            }
        }
        if (RdfFormat.isQuadFormat(format)) return mapOf(dataset)
        return mapOf(
            "parseGraph" to attempt { provider.parseGraph(doc.byteInputStream(), format, null).size() },
            "openTripleStream" to attempt { provider.openTripleStream(doc.byteInputStream(), format, null).use { it.count() } },
            dataset,
        )
    }

    private val providers: List<Pair<RdfProvider, () -> RdfRepository>> = listOf(
        JenaProvider() to { JenaRepository.MemoryRepository() },
        Rdf4jProvider() to { Rdf4jRepository.MemoryRepository() },
    )

    @TestFactory
    fun `a malformed IRI or language tag fails the parse or is kept`() = providers.flatMap { (provider, repository) ->
        formats.flatMap { format ->
            terms.map { term ->
                DynamicTest.dynamicTest("${provider.id} $format: ${term.name}") {
                    val doc = document(format, term)
                    val dropped = results(provider, repository, format, doc).filterValues { it !is Refused && it != Parsed(1) }
                    if (dropped.isNotEmpty()) fail("$doc\n" + dropped.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                }
            }
        }
    }

    @TestFactory
    fun `the well-formed control document parses in every format`() = providers.flatMap { (provider, repository) ->
        formats.map { format ->
            DynamicTest.dynamicTest("${provider.id} $format") {
                val doc = document(format, Term("control", language = "en-US"))
                val wrong = results(provider, repository, format, doc).filterValues { it != Parsed(1) }
                if (wrong.isNotEmpty()) fail("$doc\n$wrong")
            }
        }
    }
}
