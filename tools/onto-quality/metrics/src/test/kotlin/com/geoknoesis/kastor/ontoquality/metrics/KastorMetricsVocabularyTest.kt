package com.geoknoesis.kastor.ontoquality.metrics

import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import java.io.StringReader
import kotlin.test.assertTrue

class KastorMetricsVocabularyTest {
    @Test
    fun `bundled kastor-metrics ttl parses as Turtle`() {
        val text =
            javaClass.getResourceAsStream("/vocab/kastor-metrics.ttl")!!.bufferedReader().readText()
        val m = ModelFactory.createDefaultModel()
        m.read(StringReader(text), null, "TTL")
        assertTrue(m.size() > 0)
    }
}
