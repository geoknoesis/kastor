package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream

/** The start scripts' main class tells a user on an old Java which one is needed, instead of failing to link. */
class CliJavaVersionTest {
    @Test
    fun `the launcher is compiled for Java 8 so that old runtimes can load it`() {
        val resource = "/" + KastorRdfLauncher::class.java.name.replace('.', '/') + ".class"
        DataInputStream(checkNotNull(javaClass.getResourceAsStream(resource))).use { input ->
            assertEquals(0xCAFEBABE.toInt(), input.readInt())
            input.readUnsignedShort() // minor version
            assertEquals(52, input.readUnsignedShort(), "class file major version of the launcher")
        }
    }

    @Test
    fun `feature releases are read from both version schemes`() {
        assertEquals(8, KastorRdfLauncher.featureRelease("1.8"))
        assertEquals(17, KastorRdfLauncher.featureRelease("17"))
        assertEquals(21, KastorRdfLauncher.featureRelease("21"))
        assertEquals(26, KastorRdfLauncher.featureRelease("26-ea"))
        assertEquals(-1, KastorRdfLauncher.featureRelease(null))
        assertEquals(-1, KastorRdfLauncher.featureRelease("unknown"))
    }

    @Test
    fun `an older Java is refused with the version that is needed`() {
        val problem = checkNotNull(KastorRdfLauncher.javaVersionProblem("17", "/opt/jdk-17"))
        assertTrue(problem.startsWith("kastor-rdf: error: Java $REQUIRED_JAVA_VERSION or later is required"), problem)
        assertTrue(problem.contains("this is Java 17 (/opt/jdk-17)") && problem.contains("JAVA_HOME"), problem)
        assertTrue(KastorRdfLauncher.javaVersionProblem("1.8", null)!!.contains("this is Java 1.8;"))
        assertEquals(3, KastorRdfLauncher.EXIT_RUNTIME_ERROR)
        assertEquals(EXIT_RUNTIME_ERROR, KastorRdfLauncher.EXIT_RUNTIME_ERROR)
        assertEquals(REQUIRED_JAVA_VERSION, KastorRdfLauncher.REQUIRED_JAVA_VERSION)
    }

    @Test
    fun `the required Java and newer ones are accepted`() {
        assertNull(KastorRdfLauncher.javaVersionProblem("21", null))
        assertNull(KastorRdfLauncher.javaVersionProblem("25", null))
        assertNull(KastorRdfLauncher.javaVersionProblem(System.getProperty("java.specification.version"), null))
        // A version that cannot be read is left to the JVM.
        assertNull(KastorRdfLauncher.javaVersionProblem("unknown", null))
    }
}
