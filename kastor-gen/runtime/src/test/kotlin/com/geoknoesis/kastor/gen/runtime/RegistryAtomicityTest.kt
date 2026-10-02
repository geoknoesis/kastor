package com.geoknoesis.kastor.gen.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** `register` and `unregister` each change the factory and the list of registered types as one step. */
class RegistryAtomicityTest {

    private interface Probe

    @Test
    fun `a type registered while another thread unregisters it is listed exactly when it has a factory`() {
        val type = Probe::class.java
        val factory: (RdfHandle) -> Probe = { object : Probe {} }
        val rounds = 20_000
        val inconsistent = AtomicInteger()
        // Both threads start each round together; when both are done the registry must be consistent.
        val barrier = CyclicBarrier(2) {
            if (OntoMapper.isRegistered(type) != (type in OntoMapper.registeredTypes())) inconsistent.incrementAndGet()
        }
        try {
            val threads = listOf<() -> Unit>(
                { OntoMapper.register(type, replace = true, factory = factory) },
                { OntoMapper.unregister(type) },
            ).map { operation ->
                thread(isDaemon = true) {
                    repeat(rounds) {
                        barrier.await(30, TimeUnit.SECONDS)
                        operation()
                    }
                    barrier.await(30, TimeUnit.SECONDS)
                }
            }
            threads.forEach { it.join(120_000) }
            assertEquals(0, inconsistent.get(), "rounds in which a registered type was missing from registeredTypes()")
        } finally {
            OntoMapper.unregister(type)
        }
    }
}
