package com.geoknoesis.kastor.rdf.jena

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `close()` snapshots the open inference views while their workers may remove themselves (idle timeout). A
 * size-then-iterate copy threw `NoSuchElementException` when a view left between the two steps.
 */
class JenaInferenceCloseRaceTest {
    /** A set whose element was removed after `size` was read: the iterator is already exhausted. */
    private class ShrinkingSet : AbstractMutableSet<String>() {
        override val size: Int get() = 1
        override fun add(element: String) = false
        override fun iterator(): MutableIterator<String> = mutableListOf<String>().iterator()
    }

    @Test
    fun `snapshot tolerates an element removed after size was read`() {
        assertEquals(emptyList(), snapshotOf(ShrinkingSet()))
    }
}
