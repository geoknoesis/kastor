package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.query.TupleQueryResult
import org.eclipse.rdf4j.query.BindingSet as Rdf4jBindingSet

class Rdf4jResultSet(private val result: TupleQueryResult) : QueryResult {
    
    override fun iterator(): Iterator<BindingSet> {
        return object : Iterator<BindingSet> {
            override fun hasNext(): Boolean = result.hasNext()
            override fun next(): BindingSet {
                val rdf4jBindingSet = result.next()
                return Rdf4jBindingSetWrapper(rdf4jBindingSet)
            }
        }
    }
    
    override fun toList(): List<BindingSet> {
        val list = mutableListOf<BindingSet>()
        while (result.hasNext()) {
            val rdf4jBindingSet = result.next()
            list.add(Rdf4jBindingSetWrapper(rdf4jBindingSet))
        }
        return list
    }
    
    override fun first(): BindingSet? {
        return if (result.hasNext()) {
            val rdf4jBindingSet = result.next()
            Rdf4jBindingSetWrapper(rdf4jBindingSet)
        } else null
    }
    
    override fun asSequence(): Sequence<BindingSet> {
        return iterator().asSequence()
    }
    
    override fun count(): Int {
        var count = 0
        while (result.hasNext()) {
            result.next()
            count++
        }
        return count
    }
    
}

private class Rdf4jBindingSetWrapper(private val bindingSet: Rdf4jBindingSet) : BindingSet {
    
    override fun get(name: String): RdfTerm? {
        val value = bindingSet.getValue(name)
        return if (value != null) Rdf4jTerms.fromRdf4jValue(value) else null
    }
    
    override fun getVariableNames(): Set<String> = bindingSet.bindingNames.toSet()
    
    override fun hasBinding(variable: String): Boolean = bindingSet.hasBinding(variable)
    
    override fun getString(name: String): String? {
        val term = get(name)
        return if (term is Literal) term.lexical else null
    }
    
    override fun getInt(name: String): Int? {
        val term = get(name)
        return if (term is Literal) term.lexical.toIntOrNull() else null
    }
    
    override fun getDouble(name: String): Double? {
        val term = get(name)
        return if (term is Literal) term.lexical.toDoubleOrNull() else null
    }
    
    override fun getBoolean(name: String): Boolean? {
        val term = get(name)
        return if (term is Literal) term.lexical.toBooleanStrictOrNull() else null
    }
}
