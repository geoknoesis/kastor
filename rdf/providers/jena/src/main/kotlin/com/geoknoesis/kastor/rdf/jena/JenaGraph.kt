package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.Model

internal class JenaGraph(val model: Model, private val repository: JenaRepository? = null) : MutableRdfGraph {
    private fun <T> read(block: (Model) -> T): T = repository?.withRead { block(repository.readModel(model)) } ?: block(model)
    private fun <T> write(block: () -> T): T = repository?.withWrite(block) ?: block()
    override fun addTriple(triple: RdfTriple): Unit = write {
        model.add(JenaTerms.toResource(model, triple.subject), JenaTerms.toProperty(model, triple.predicate), JenaTerms.toNode(model, triple.obj))
        Unit
    }
    override fun addTriples(triples: Collection<RdfTriple>): Unit = write { triples.forEach(::addTriple) }
    override fun removeTriple(triple: RdfTriple): Boolean = write {
        val s = JenaTerms.toResource(model, triple.subject)
        val p = JenaTerms.toProperty(model, triple.predicate)
        val o = JenaTerms.toNode(model, triple.obj)
        val exists = model.contains(s, p, o)
        if (exists) model.remove(s, p, o)
        exists
    }
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = write {
        var changed = false
        triples.forEach { if (removeTriple(it)) changed = true }
        changed
    }
    override fun hasTriple(triple: RdfTriple): Boolean = read {
        it.contains(JenaTerms.toResource(it, triple.subject), JenaTerms.toProperty(it, triple.predicate), JenaTerms.toNode(it, triple.obj))
    }
    override fun getTriples(): List<RdfTriple> = find()
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> = read { view ->
        val iterator = view.listStatements(subject?.let { JenaTerms.toResource(view, it) },
            predicate?.let { JenaTerms.toProperty(view, it) }, obj?.let { JenaTerms.toNode(view, it) })
        try {
            iterator.asSequence().map {
                RdfTriple(JenaTerms.fromResource(it.subject), JenaTerms.fromProperty(it.predicate), JenaTerms.fromNode(it.`object`))
            }.toList()
        } finally { iterator.close() }
    }
    override fun clear(): Boolean = write { val changed = !model.isEmpty; model.removeAll(); changed }
    override fun size(): Int = read { Math.toIntExact(it.size()) }
}
