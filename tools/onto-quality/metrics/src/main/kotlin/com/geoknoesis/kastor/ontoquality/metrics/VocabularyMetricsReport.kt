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
    /** The text report; [sections] limits it to some sections (the header is always kept). */
    @JvmOverloads
    fun describeText(sections: Set<MetricsSection> = MetricsSection.ALL): String = TextRenderer.render(this, sections)

    /** The Markdown report; [sections] limits it to some sections (the header is always kept). */
    @JvmOverloads
    fun describeMarkdown(sections: Set<MetricsSection> = MetricsSection.ALL): String = MarkdownRenderer.render(this, sections)

    fun toJson(): String = JsonSerializer.toJson(this)

    fun toTurtle(): String = TurtleSerializer.toTurtle(this)

    companion object {
        const val MODULE_VERSION = "0.1.0"
        const val OQUARE_VERSION = "Duque-Ramos 2014"
    }
}

/** A section of the rendered report: graph counts, OWL (OQuaRE and Kastor-adapted) metrics, SKOS metrics. */
enum class MetricsSection {
    GRAPH,
    OWL,
    SKOS,
    ;

    companion object {
        /** Every section: the whole report. */
        val ALL: Set<MetricsSection> = java.util.Collections.unmodifiableSet(java.util.EnumSet.allOf(MetricsSection::class.java))
    }
}
