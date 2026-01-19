package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.QueryResult

class Rdf4jResultSet(private val rows: List<BindingSet>) : QueryResult {
    override fun iterator(): Iterator<BindingSet> = rows.iterator()
    override fun toList(): List<BindingSet> = rows.toList()
    override fun first(): BindingSet? = rows.firstOrNull()
    override fun asSequence(): Sequence<BindingSet> = rows.asSequence()
    override fun count(): Int = rows.size
}









