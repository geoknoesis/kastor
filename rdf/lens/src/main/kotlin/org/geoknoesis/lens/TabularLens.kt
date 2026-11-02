package org.geoknoesis.lens

/**
 * A typed, provider-independent algebra for tabular views of node selections.
 *
 * Projection preserves all distinct (node, projected-value) combinations.
 * GroupCount counts distinct selected nodes per tuple of dimension values.
 * No intermediate node sets are joined in the Kotlin application.
 */
sealed interface TabularLens {
    val source: Lens

    data class Column(
        val alias: String,
        val predicate: String,
        val required: Boolean = false
    ) {
        init {
            require(alias.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid column alias" }
            require(alias !in setOf("node", "count", "value")) { "Reserved column alias: $alias" }
        }
    }

    data class Projection(
        override val source: Lens,
        val columns: List<Column>
    ) : TabularLens {
        init {
            require(columns.size in 1..8) { "Projection requires 1..8 columns" }
            require(columns.map { it.alias }.distinct().size == columns.size) { "Duplicate column alias" }
        }
    }

    data class Count(override val source: Lens) : TabularLens

    /** A single-property count distribution, optionally including the unbound bucket. */
    data class Facet(
        override val source: Lens,
        val predicate: String,
        val includeMissing: Boolean = false
    ) : TabularLens

    /** Count distinct selected nodes per combination of at most three dimensions. */
    data class GroupCount(
        override val source: Lens,
        val dimensions: List<Column>
    ) : TabularLens {
        init {
            require(dimensions.size in 1..3) { "GroupCount requires 1..3 dimensions" }
            require(dimensions.map { it.alias }.distinct().size == dimensions.size) { "Duplicate dimension alias" }
        }
    }
}

object TabularDsl {
    fun project(source: Lens, vararg columns: TabularLens.Column): TabularLens =
        TabularLens.Projection(source, columns.toList())

    fun count(source: Lens): TabularLens = TabularLens.Count(source)

    fun facet(source: Lens, predicate: String, includeMissing: Boolean = false): TabularLens =
        TabularLens.Facet(source, predicate, includeMissing)

    fun groupCount(source: Lens, vararg dimensions: TabularLens.Column): TabularLens =
        TabularLens.GroupCount(source, dimensions.toList())
}

/**
 * Compiles only typed IRIs and variable names. SPARQL evaluation, including
 * grouping and counting, occurs inside the provider.
 */
class TabularLensCompiler(
    private val selector: SparqlCompiler = SparqlCompiler(),
    private val maxQueryChars: Int = 100_000
) {
    data class Compiled(val sparql: String, val variables: List<String>)

    init { require(maxQueryChars >= 256) }

    private fun iri(raw: String): String {
        require(raw.startsWith("urn:") || raw.startsWith("https://") || raw.startsWith("http://")) {
            "Absolute IRI required"
        }
        require(raw.none {
            it.isWhitespace() || it.code in setOf(60, 62, 34, 123, 125, 124, 94, 92, 96)
        }) { "Unsafe IRI" }
        return "<$raw>"
    }

    private fun selection(source: Lens): String =
        "{ SELECT DISTINCT ?node WHERE {\n" + selector.graphPattern(source, "?node") + "\n} }"

    private fun pattern(column: TabularLens.Column): String {
        val triple = "?node " + iri(column.predicate) + " ?" + column.alias + " ."
        return if (column.required) triple else "OPTIONAL { $triple }"
    }

    private fun group(source: Lens, dimensions: List<TabularLens.Column>): Compiled {
        val names = dimensions.map { "?" + it.alias }
        val projection = names.joinToString(" ")
        val groupBy = projection
        val body = dimensions.joinToString("\n") { pattern(it) }
        val query = "SELECT $projection (COUNT(DISTINCT ?node) AS ?count) WHERE {\n" +
            selection(source) + "\n" + body +
            "\n} GROUP BY $groupBy ORDER BY DESC(?count) $groupBy"
        return Compiled(query, dimensions.map { it.alias } + "count")
    }

    fun compile(lens: TabularLens): Compiled {
        val compiled = when (lens) {
            is TabularLens.Count -> Compiled(
                "SELECT (COUNT(DISTINCT ?node) AS ?count) WHERE {\n" +
                    selection(lens.source) + "\n}", listOf("count")
            )
            is TabularLens.Projection -> {
                val columns = lens.columns
                val vars = listOf("node") + columns.map { it.alias }
                val names = vars.joinToString(" ") { "?$it" }
                val query = "SELECT DISTINCT $names WHERE {\n" + selection(lens.source) +
                    "\n" + columns.joinToString("\n") { pattern(it) } +
                    "\n} ORDER BY $names"
                Compiled(query, vars)
            }
            is TabularLens.Facet -> group(
                lens.source, listOf(TabularLens.Column("facetValue", lens.predicate, !lens.includeMissing))
            )
            is TabularLens.GroupCount -> group(lens.source, lens.dimensions)
        }
        require(compiled.sparql.length <= maxQueryChars) { "Tabular query exceeds size budget" }
        return compiled
    }
}
