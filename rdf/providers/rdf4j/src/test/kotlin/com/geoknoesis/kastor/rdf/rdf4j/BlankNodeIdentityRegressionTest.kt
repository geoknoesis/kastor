package com.geoknoesis.kastor.rdf.rdf4j

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.security.MessageDigest

class BlankNodeIdentityRegressionTest {
    @Test fun `long label and its hash remain distinct blank nodes`() {
        val longLabel = "a".repeat(33)
        val hashLabel = MessageDigest.getInstance("MD5").digest(longLabel.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val input = "_:${longLabel} <urn:example:p> <urn:example:one> .\n_:${hashLabel} <urn:example:p> <urn:example:two> .\n"
        val graph = Rdf4jProvider().parseGraph(input.byteInputStream(), "N-TRIPLES")
        assertEquals(2, graph.getTriples().map { it.subject }.toSet().size,
            "Different source blank-node labels must not be merged during parsing")
    }
}
