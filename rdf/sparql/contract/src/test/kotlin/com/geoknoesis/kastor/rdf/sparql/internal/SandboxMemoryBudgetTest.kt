package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxMemoryBudgetTest {

    @Test
    fun `charges accumulate and fail once past the limit`() {
        val budget = SandboxMemoryBudget(limit = 100)
        budget.charge(60, "CONCAT")
        budget.charge(40, "CONCAT")
        assertEquals(100, budget.charged)
        val error = assertThrows<SandboxLimitException> { budget.charge(1, "UCASE") }
        assertTrue("UCASE" in error.message.orEmpty() && "100" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `require checks a value before it is built, without charging it`() {
        val budget = SandboxMemoryBudget(limit = 100)
        budget.charge(90, "CONCAT")
        budget.require(10, "CONCAT")
        assertEquals(90, budget.charged)
        assertThrows<SandboxLimitException> { budget.require(11, "CONCAT") }
    }

    @Test
    fun `numbers of a fixed size cost nothing, larger ones one character per 16 bits`() {
        assertEquals(0, SandboxMemoryBudget.numberSize(64))
        assertEquals(1, SandboxMemoryBudget.numberSize(65))
        assertEquals(4, SandboxMemoryBudget.numberSize(128))
        assertEquals(64L * 1024 * 1024, SandboxLimits.MAX_COMPUTED_CHARACTERS)
    }
}
