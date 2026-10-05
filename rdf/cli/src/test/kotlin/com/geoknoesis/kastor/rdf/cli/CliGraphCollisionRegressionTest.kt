package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CliGraphCollisionRegressionTest {
    @TempDir lateinit var tmp: Path
    @Test fun `default graph cannot alias a legal named graph`() {
        val a = tmp.resolve("a.nq")
        val b = tmp.resolve("b.nq")
        Files.writeString(a, "<urn:example:a> <urn:example:p> <urn:example:o> .\n<urn:example:b> <urn:example:p> <urn:example:o> <urn:kastor:rdf-cli:default-graph> .\n")
        Files.writeString(b, "<urn:example:b> <urn:example:p> <urn:example:o> .\n<urn:example:a> <urn:example:p> <urn:example:o> <urn:kastor:rdf-cli:default-graph> .\n")
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val code = runCli(listOf("diff", a.toString(), b.toString()), PrintStream(output), PrintStream(errors))
        assertEquals(EXIT_NOT_ISOMORPHIC, code, output.toString() + errors.toString())
    }
}
