package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigDiagnosticRedactionTest {
    private val secret = "DO_NOT_PRINT_THIS_SECRET"
    private val options = mapOf(
        "password" to secret, "header.Authorization" to "Bearer $secret",
        "header.Cookie" to secret, "customProviderCredential" to secret,
        "location" to "https://user:$secret@example.org/query?token=$secret",
    )

    @Test
    fun `config copies and provider selection diagnostics never print option values`() {
        val config = RdfConfig(options = options)
        assertFalse(config.toString().contains(secret))
        assertFalse(config.copy().toString().contains(secret))
        val registry = DefaultProviderRegistry(autoDiscover = false, registerDefaultMemoryProvider = false)
        val error = assertThrows(IllegalArgumentException::class.java) { registry.create(config) }
        assertFalse(error.message!!.contains(secret))
        assertTrue(error.message!!.contains("No provider found"))
        assertEquals(options, config.options, "Redaction must not change the actual credentials")
    }
}
