package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.ontoquality.metrics.serialize.JsonSerializer
import com.geoknoesis.kastor.ontoquality.metrics.serialize.MarkdownRenderer
import com.geoknoesis.kastor.ontoquality.metrics.serialize.TextRenderer
import com.geoknoesis.kastor.ontoquality.metrics.serialize.TurtleSerializer
import java.time.Instant

data class VocabularyMetricsReport(
    val graph: GraphMetricsSection,
    val owl: OwlMetricsSection,
    val skos: SkosMetricsSection,
    val moduleVersion: String,
    val oquareVersion: String,
    val computedAt: Instant,
) {
    fun describeText(): String = TextRenderer.render(this)

    fun describeMarkdown(): String = MarkdownRenderer.render(this)

    fun toJson(): String = JsonSerializer.toJson(this)

    fun toTurtle(): String = TurtleSerializer.toTurtle(this)

    companion object {
        const val MODULE_VERSION = "0.1.0"
        const val OQUARE_VERSION = "Duque-Ramos 2014"
    }
}
