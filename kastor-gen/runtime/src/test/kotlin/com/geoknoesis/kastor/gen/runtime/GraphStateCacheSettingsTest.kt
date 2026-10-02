@file:OptIn(KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * The cache size property shared by the validation adapters: an invalid value is reported once per property and
 * value and the default is used. The warnings are captured through the cache's own sink and the "already reported"
 * memory is reset around each test, so nothing depends on the logging backend or on what ran before in this JVM.
 */
class GraphStateCacheSettingsTest {

    private val property = "kastor.test.graphStateCache.maxEntries"
    private val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())

    @BeforeEach
    fun capture() {
        GraphStateCache.resetReportedSettings()
        GraphStateCache.warningSink = { warnings += it }
    }

    @AfterEach
    fun restore() {
        GraphStateCache.warningSink = null
        GraphStateCache.resetReportedSettings()
        System.clearProperty(property)
    }

    @Test
    fun `an unset or valid property is used without a warning`() {
        assertEquals(16, GraphStateCache.configuredMaxEntries(property, 16))
        System.setProperty(property, " 5 ")
        assertEquals(5, GraphStateCache.configuredMaxEntries(property, 16))
        assertEquals(emptyList<String>(), warnings.toList())
    }

    @Test
    fun `an invalid value is reported once per value and the default is used`() {
        for (invalid in listOf("0", "abc", "-3")) {
            System.setProperty(property, invalid)
            repeat(3) { assertEquals(16, GraphStateCache.configuredMaxEntries(property, 16)) }
            val reported = warnings.filter { "=\"$invalid\"" in it }
            assertEquals(1, reported.size, "one warning for $invalid, however often it is read")
            assertTrue(property in reported.single(), reported.single())
            assertTrue("Using the default, 16." in reported.single(), reported.single())
        }
        assertEquals(3, warnings.size)
    }

    @Test
    fun `settings reject negative values`() {
        assertThrows(IllegalArgumentException::class.java) { GraphStateCache.Settings(maxTemporaryStates = -1) }
        assertThrows(IllegalArgumentException::class.java) { GraphStateCache.Settings(temporaryWaitMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { GraphStateCache.Settings(overflowWaitMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { GraphStateCache.Settings(loadWaitMillis = -1) }
    }
}
