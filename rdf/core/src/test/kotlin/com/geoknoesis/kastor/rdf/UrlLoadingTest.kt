package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException

class UrlLoadingTest {

    @TempDir
    lateinit var dir: Path

    private fun ntriplesFile(): String {
        val file = dir.resolve("data.nt")
        Files.writeString(file, "<urn:s> <urn:p> <urn:o> .\n")
        return file.toUri().toString()
    }

    @Test
    fun `non-http schemes are rejected by default`() {
        val fileUrl = ntriplesFile()
        val error = assertThrows(IllegalArgumentException::class.java) { Rdf.parseFromUrl(fileUrl, RdfFormat.N_TRIPLES) }
        assertTrue(error.message!!.contains("file"))
        assertThrows(IllegalArgumentException::class.java) { Rdf.parseFromUrl("jar:$fileUrl!/x.ttl") }
        assertThrows(IllegalArgumentException::class.java) { Rdf.parseDatasetFromUrl(fileUrl, RdfFormat.N_QUADS) }
        assertThrows(IllegalArgumentException::class.java) { Rdf.parseFromUrl("relative/path.ttl") }

        val async = Rdf.parseFromUrlAsync(fileUrl, RdfFormat.N_TRIPLES)
        val failure = assertThrows(ExecutionException::class.java) { async.get() }
        assertTrue(failure.cause is IllegalArgumentException)
    }

    @Test
    fun `other schemes can be opted into explicitly`() {
        val options = UrlLoadOptions(allowedSchemes = setOf("file"))
        assertEquals(1, Rdf.parseFromUrl(ntriplesFile(), RdfFormat.N_TRIPLES, options).size())
        assertEquals(1, Rdf.parseFromUrlAsync(ntriplesFile(), RdfFormat.N_TRIPLES, options = options).get().size())
    }

    @Test
    fun `bodies over the size limit are rejected`() {
        val options = UrlLoadOptions(allowedSchemes = setOf("file"), maxBytes = 10)
        assertThrows(RdfInputTooLargeException::class.java) { Rdf.parseFromUrl(ntriplesFile(), RdfFormat.N_TRIPLES, options) }

        val bounded = BoundedInputStream(ByteArrayInputStream(ByteArray(100)), 50)
        assertThrows(RdfInputTooLargeException::class.java) { bounded.readBytes() }
        assertEquals(50, BoundedInputStream(ByteArrayInputStream(ByteArray(50)), 50).readBytes().size)
    }

    @Test
    fun `options are validated`() {
        assertThrows(IllegalArgumentException::class.java) { UrlLoadOptions(allowedSchemes = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { UrlLoadOptions(maxBytes = 0) }
        assertEquals(setOf("http", "https"), UrlLoadOptions.DEFAULT.allowedSchemes)
    }
}
