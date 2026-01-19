package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.QueryResult

class JenaResultSet(private val rows: List<BindingSet>) : QueryResult {
    override fun iterator(): Iterator<BindingSet> = rows.iterator()
    override fun toList(): List<BindingSet> = rows.toList()
    override fun first(): BindingSet? = rows.firstOrNull()
    override fun asSequence(): Sequence<BindingSet> = rows.asSequence()
    override fun count(): Int = rows.size
}









