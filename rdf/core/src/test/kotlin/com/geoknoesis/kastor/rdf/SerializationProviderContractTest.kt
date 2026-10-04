package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Isolated
import java.io.IOException

/** Exercises public serialization dispatch with providers whose failures and output are controlled. */
@Isolated("Temporarily replaces the global provider registry")
class SerializationProviderContractTest {
    private class Serializer(
        override val id: String,
        private val outputs: List<String> = listOf("TURTLE", "TRIG"),
        private val inputs: List<String> = emptyList(),
        private val write: (Any, String, SerializationOptions) -> String,
    ) : RdfProvider {
        override fun variants() = listOf(RdfVariant("memory"))
        override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = MemoryRepository(config)
        override fun getCapabilities(variantId: String?) = ProviderCapabilities(
            supportedInputFormats = inputs, supportedOutputFormats = outputs,
        )
        override fun serializeGraph(graph: RdfGraph, format: String, options: SerializationOptions) = write(graph, format, options)
        override fun serializeDataset(repository: RdfRepository, format: String, options: SerializationOptions) = write(repository, format, options)
    }
    private fun <T> withProviders(vararg providers: RdfProvider, block: () -> T): T {
        val registry = DefaultProviderRegistry(autoDiscover = false, registerDefaultMemoryProvider = false)
        providers.forEach(registry::register)
        RdfProviderRegistry.setDelegate(registry)
        try { return block() } finally { RdfProviderRegistry.resetDelegate() }
    }
    private fun serialize(repo: RdfRepository, dataset: Boolean): String =
        if (dataset) repo.serializeDataset(RdfFormat.TRIG) else repo.defaultGraph.serialize(RdfFormat.TURTLE)

    @TestFactory
    fun `unsupported operations fall through and explicit output formats control selection`() = listOf(false, true).map { dataset ->
        dynamicTest("dataset=$dataset") {
            val calls = mutableListOf<String>()
            val reader = Serializer("reader", outputs = listOf("N-TRIPLES"), inputs = listOf("TURTLE", "TRIG")) { _, _, _ ->
                fail<String>("Provider was selected for a format it only reads")
            }
            val declining = Serializer("declining") { _, _, _ -> calls += "declining"; throw UnsupportedOperationException("cannot write") }
            val working = Serializer("working") { _, _, _ -> calls += "working"; "serialized" }
            withProviders(reader, declining, working) {
                MemoryRepository(RdfConfig()).use { repo -> assertEquals("serialized", serialize(repo, dataset)) }
            }
            assertEquals(listOf("declining", "working"), calls)
        }
    }

    @TestFactory
    fun `legacy providers without output declarations retain input-format serialization fallback`() = listOf(false, true).map { dataset ->
        dynamicTest("dataset=$dataset") {
            val legacy = Serializer("legacy", outputs = emptyList(), inputs = listOf("TURTLE", "TRIG")) { _, _, _ -> "legacy output" }
            withProviders(legacy) {
                MemoryRepository(RdfConfig()).use { repo -> assertEquals("legacy output", serialize(repo, dataset)) }
            }
        }
    }

    @TestFactory
    fun `format errors propagate unchanged and unexpected failures preserve their cause`() = listOf(false, true).flatMap { dataset ->
        listOf<Exception>(RdfFormatException.Generic("format failure"), IOException("write failure")).map { original ->
            dynamicTest("dataset=$dataset ${original.javaClass.simpleName}") {
                val broken = Serializer("broken") { _, _, _ -> throw original }
                val fallback = Serializer("fallback") { _, _, _ -> fail<String>("A real failure must not be hidden by fallback") }
                withProviders(broken, fallback) {
                    MemoryRepository(RdfConfig()).use { repo ->
                        val error = assertThrows(RdfFormatException::class.java) { serialize(repo, dataset) }
                        if (original is RdfFormatException) assertSame(original, error)
                        else {
                            assertSame(original, error.cause)
                            assertEquals(RdfErrorCode.FORMAT_SERIALIZATION_ERROR, error.errorCode)
                            assertTrue(error.message.orEmpty().contains("broken"))
                        }
                    }
                }
            }
        }
    }

    @TestFactory
    fun `exhausted providers report the requested format and available formats`() = listOf(false, true).map { dataset ->
        dynamicTest("dataset=$dataset") {
            val declining = Serializer("declining") { _, _, _ -> throw UnsupportedOperationException() }
            val legacy = Serializer("legacy", outputs = emptyList(), inputs = listOf("JSON-LD")) { _, _, _ -> fail<String>("Wrong format") }
            withProviders(declining, legacy) {
                MemoryRepository(RdfConfig()).use { repo ->
                    val error = assertThrows(RdfFormatException.UnsupportedFormat::class.java) { serialize(repo, dataset) }
                    assertEquals(if (dataset) "TRIG" else "TURTLE", error.format)
                    assertEquals(listOf("TURTLE", "TRIG", "JSON-LD"), error.availableFormats)
                }
            }
        }
    }

    @TestFactory
    fun `string and enum overloads forward every option and the original data source`() = listOf(false, true).flatMap { dataset ->
        listOf(false, true).map { stringFormat ->
            dynamicTest("dataset=$dataset stringFormat=$stringFormat") {
                val configure: SerializationOptions.Builder.() -> Unit = {
                    prettyPrint = false
                    baseUri = "https://example.org/"
                    prefix("ex", "https://example.org/")
                    useAbbreviatedSyntax = false
                    lineWidth = 0
                    jsonLdContext = "{}"
                    jsonLdCompact = true
                    jsonLdFrame = "{}"
                }
                val expected = SerializationOptions(false, "https://example.org/", mapOf("ex" to "https://example.org/"), false, 0, "{}", true, "{}")
                MemoryRepository(RdfConfig()).use { repo ->
                    val graph = repo.defaultGraph
                    val recorder = Serializer("recorder") { source, format, options ->
                        assertSame(if (dataset) repo else graph, source)
                        assertEquals(if (dataset) "TRIG" else "TURTLE", format)
                        assertEquals(expected, options)
                        "serialized"
                    }
                    withProviders(recorder) {
                        val result = if (dataset) {
                            if (stringFormat) repo.serializeDataset("trig", configure) else repo.serializeDataset(RdfFormat.TRIG, configure)
                        } else {
                            if (stringFormat) graph.serialize("ttl", configure) else graph.serialize(RdfFormat.TURTLE, configure)
                        }
                        assertEquals("serialized", result)
                        assertEquals("serialized", if (dataset) repo.serializeDataset("trig", expected) else graph.serialize("ttl", expected))
                    }
                }
            }
        }
    }

    @TestFactory
    fun `datasets reject graph-only formats before asking a provider to serialize`() = listOf(false, true).flatMap { stringFormat ->
        listOf(false, true).map { configure ->
            dynamicTest("stringFormat=$stringFormat configure=$configure") {
                val provider = Serializer("must-not-run") { _, _, _ -> fail<String>("Invalid dataset format reached provider") }
                withProviders(provider) {
                    MemoryRepository(RdfConfig()).use { repo ->
                        assertThrows(IllegalArgumentException::class.java) {
                            if (stringFormat) {
                                if (configure) repo.serializeDataset("ttl") { prettyPrint = false } else repo.serializeDataset("ttl")
                            } else {
                                if (configure) repo.serializeDataset(RdfFormat.TURTLE) { prettyPrint = false } else repo.serializeDataset(RdfFormat.TURTLE)
                            }
                        }
                    }
                }
            }
        }
    }
}
