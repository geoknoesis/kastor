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

    /**
     * Numeric aggregation over one RDF property, optionally grouped by dimensions.
     * Each distinct (node, dimension tuple, numeric RDF term) contributes once.
     * Non-numeric terms are excluded; missing numeric values remain unbound.
     */
    data class NumericAggregate(
        override val source: Lens,
        val predicate: String,
        val operation: NumericOperation,
        val alias: String = "aggregate",
        val dimensions: List<Column> = emptyList()
    ) : TabularLens {
        init {
            require(alias.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid aggregate alias" }
            require(alias !in setOf("node", "count", "value")) { "Reserved aggregate alias" }
            require(dimensions.size <= 3) { "NumericAggregate supports at most 3 dimensions" }
            require(dimensions.map { it.alias }.distinct().size == dimensions.size) {
                "Duplicate dimension aliases"
            }
            require(dimensions.none { it.alias == alias }) { "Aggregate and dimension aliases collide" }
        }
    }

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

/** Fixed, typed SPARQL aggregate operators; never accept arbitrary function text. */
enum class NumericOperation(val sparql: String) {
    SUM("SUM"), AVG("AVG"), MIN("MIN"), MAX("MAX")
}

object TabularDsl {
    fun project(source: Lens, vararg columns: TabularLens.Column): TabularLens =
        TabularLens.Projection(source, columns.toList())

    fun count(source: Lens): TabularLens = TabularLens.Count(source)

    fun facet(source: Lens, predicate: String, includeMissing: Boolean = false): TabularLens =
        TabularLens.Facet(source, predicate, includeMissing)

    fun groupCount(source: Lens, vararg dimensions: TabularLens.Column): TabularLens =
        TabularLens.GroupCount(source, dimensions.toList())

    fun aggregate(
        source: Lens,
        predicate: String,
        operation: NumericOperation,
        alias: String = "aggregate",
        vararg dimensions: TabularLens.Column
    ): TabularLens = TabularLens.NumericAggregate(
        source, predicate, operation, alias, dimensions.toList()
    )

    fun sum(source: Lens, predicate: String, vararg dimensions: TabularLens.Column): TabularLens =
        aggregate(source, predicate, NumericOperation.SUM, "sum", *dimensions)

    fun avg(source: Lens, predicate: String, vararg dimensions: TabularLens.Column): TabularLens =
        aggregate(source, predicate, NumericOperation.AVG, "avg", *dimensions)

    fun min(source: Lens, predicate: String, vararg dimensions: TabularLens.Column): TabularLens =
        aggregate(source, predicate, NumericOperation.MIN, "min", *dimensions)

    fun max(source: Lens, predicate: String, vararg dimensions: TabularLens.Column): TabularLens =
        aggregate(source, predicate, NumericOperation.MAX, "max", *dimensions)
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
            it.isWhitespace() || it.code == 60 || it.code == 62 || it.code == 34 || 
                it.code == 123 || it.code == 125 || it.code == 124 || it.code == 94 || 
                it.code == 92 || it.code == 96
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

    /**
     * Pre-deduplicate whole (node, dimensions, value) tuples, not metric values:
     * SUM(DISTINCT ?metric) would incorrectly collapse equal amounts from
     * different nodes. Grouping and aggregation run entirely at the provider.
     */
    private fun numericAggregate(lens: TabularLens.NumericAggregate): Compiled {
        val dimensions = lens.dimensions
        val dimensionNames = dimensions.map { "?" + it.alias }
        val measure = "?__lensNumericValue"
        val selected = (listOf("?node") + dimensionNames + measure).joinToString(" ")
        val sourcePatterns = dimensions.joinToString("\n") { pattern(it) }
        val numeric = "OPTIONAL { ?node " + iri(lens.predicate) + " " + measure +
            " . FILTER(isNumeric(" + measure + ")) }"
        val deduplicated = "{ SELECT DISTINCT " + selected + " WHERE {\n" +
            selection(lens.source) + "\n" + sourcePatterns + "\n" + numeric + "\n} }"
        val aggregateExpression = "(" + lens.operation.sparql + "(" + measure + ") AS ?" +
            lens.alias + ")"
        val prefix = if (dimensionNames.isEmpty()) "" else dimensionNames.joinToString(" ") + " "
        val suffix = if (dimensionNames.isEmpty()) "" else
            " GROUP BY " + dimensionNames.joinToString(" ") +
                " ORDER BY " + dimensionNames.joinToString(" ")
        val query = "SELECT " + prefix + aggregateExpression + " WHERE {\n" +
            deduplicated + "\n}" + suffix
        return Compiled(query, dimensions.map { it.alias } + lens.alias)
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
            is TabularLens.NumericAggregate -> numericAggregate(lens)
        }
        require(compiled.sparql.length <= maxQueryChars) { "Tabular query exceeds size budget" }
        return compiled
    }
}
