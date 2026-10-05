package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

class JenaPartialUpdateRegressionTest {
    @TempDir lateinit var tmp: Path

    @Test fun `caught partial update keeps new graphs reachable`() {
        val input = tmp.resolve("input.nq")
        Files.writeString(input, "<urn:example:s> <urn:example:p> <urn:example:o> _:g .\n")
        JenaRepository.MemoryRepository().use { repo ->
            repo.transaction {
                assertThrows(RdfQueryException::class.java) {
                    update(UpdateQuery("LOAD <${input.toUri()}> ; LOAD <${tmp.resolve("missing.nq").toUri()}>"))
                }
            }
            assertTrue(repo.ask(SparqlAskQuery("ASK { GRAPH ?g { <urn:example:s> <urn:example:p> <urn:example:o> } }")),
                "The successful LOAD is still stored and queryable")
            assertEquals(1, repo.listGraphs().size,
                "The successful first LOAD must remain reachable after the caught second LOAD failure")
            assertEquals(1, repo.getGraph(repo.listGraphs().single()).size())
        }
    }
}
