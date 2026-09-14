package com.geoknoesis.kastor.gen.runtime

import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.test.assertEquals

/**
 * SLF4J is only needed when something is actually logged (a class-reloading factory replacement, a value skipped under
 * `IllTypedValueHandling.SKIP`): generated wrappers must load `OntoMapper` and throw `MaterializationException` on a
 * class path without SLF4J, as consumers with a flat file class path do.
 */
class RuntimeWithoutSlf4jTest {

    private fun loaderWithoutSlf4j(): ClassLoader {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator)
            .filterNot { it.contains("slf4j", ignoreCase = true) || it.contains("logback", ignoreCase = true) }
            .map { File(it).toURI().toURL() }
        return URLClassLoader(entries.toTypedArray(), ClassLoader.getPlatformClassLoader())
    }

    @Test
    fun `OntoMapper and MaterializationPolicy work without SLF4J on the class path`() {
        val loader = loaderWithoutSlf4j()
        val missing = runCatching { Class.forName("org.slf4j.Logger", false, loader) }.exceptionOrNull()
        assertEquals("java.lang.ClassNotFoundException", missing?.javaClass?.name, "precondition: SLF4J is not visible")

        val mapper = Class.forName("com.geoknoesis.kastor.gen.runtime.OntoMapper", true, loader)
        mapper.methods.first { it.name == "isRegistered" }.invoke(null, String::class.java)

        val policy = Class.forName("com.geoknoesis.kastor.gen.runtime.MaterializationPolicy", true, loader)
        val thrown = try {
            policy.getMethod("missingRequired", String::class.java).invoke(null, "title")
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
        assertEquals("com.geoknoesis.kastor.gen.runtime.MaterializationException", thrown?.javaClass?.name)
    }
}
