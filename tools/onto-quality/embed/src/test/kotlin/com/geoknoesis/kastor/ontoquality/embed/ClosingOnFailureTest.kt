package com.geoknoesis.kastor.ontoquality.embed

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The cleanup [OnnxEmbeddingModel] runs when reading its half-initialised native session fails. */
class ClosingOnFailureTest {
    private class Resource(val name: String, val log: MutableList<String>, val failure: Exception? = null) : AutoCloseable {
        override fun close() {
            log += name
            failure?.let { throw it }
        }
    }

    @Test
    fun `nothing is closed when the block succeeds`() {
        val log = mutableListOf<String>()
        assertEquals(listOf("input_ids"), closingOnFailure(Resource("tokenizer", log), Resource("session", log)) { listOf("input_ids") })
        assertEquals(emptyList(), log)
    }

    @Test
    fun `every resource is closed in order when the block throws, and the failure is rethrown`() {
        val log = mutableListOf<String>()
        val inputNamesFailure = IllegalStateException("OrtException: session.inputNames")
        val closeFailure = IllegalStateException("tokenizer close")
        val thrown =
            assertFailsWith<IllegalStateException> {
                closingOnFailure(Resource("tokenizer", log, closeFailure), Resource("session", log)) { throw inputNamesFailure }
            }
        assertSame(inputNamesFailure, thrown)
        assertEquals(listOf("tokenizer", "session"), log, "a failing close does not keep the session open")
        assertEquals(listOf<Throwable>(closeFailure), thrown.suppressed.toList())
    }
}
