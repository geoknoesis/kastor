package com.geoknoesis.kastor.ontoquality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretRedactionTest {
    @Test
    fun `long secrets are replaced everywhere, longest first`() {
        val text = "key sk-abcdef-0123456789 and its prefix sk-abcdef-01"
        assertEquals("key *** and its prefix ***", SecretRedaction.redact(text, listOf("sk-abcdef-01", "sk-abcdef-0123456789")))
        assertFalse(SecretRedaction.isShort("12345678"))
        assertTrue(SecretRedaction.isShort("1234567"))
    }

    @Test
    fun `short secrets are replaced only as a whole token in a credential position`() {
        val secrets = listOf("1")
        assertEquals("HTTP 401, 1 of 11 batches, port 11434", SecretRedaction.redact("HTTP 401, 1 of 11 batches, port 11434", secrets))
        assertEquals("Authorization: Bearer ***", SecretRedaction.redact("Authorization: Bearer 1", secrets))
        assertEquals("api-key=*** x-api-key: *** token '***'", SecretRedaction.redact("api-key=1 x-api-key: 1 token '1'", secrets))
        // Not a whole token: 12 is another value.
        assertEquals("key=12", SecretRedaction.redact("key=12", secrets))
    }

    @Test
    fun `URL credentials are always replaced, blank secrets are ignored`() {
        assertEquals("see https://***@host/x and ftp://***@h", SecretRedaction.redact("see https://u:p@host/x and ftp://anonymous@h", listOf("", " ")))
        assertEquals(listOf("u:p", "p"), SecretRedaction.urlCredentials("https://u:p@host/x"))
        assertEquals(emptyList<String>(), SecretRedaction.urlCredentials("https://host/x"))
        assertEquals("no url here", SecretRedaction.redactUrlCredentials("no url here"))
    }
}
