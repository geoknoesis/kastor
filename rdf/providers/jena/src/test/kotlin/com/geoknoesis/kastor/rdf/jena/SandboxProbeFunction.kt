package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import java.util.concurrent.atomic.AtomicInteger

/** A `java:` function for [JenaQuerySandboxTest]: counts how often Jena instantiates it by class name. */
class SandboxProbeFunction : FunctionBase1() {
    init { instances.incrementAndGet() }

    override fun exec(v: NodeValue): NodeValue = v

    companion object {
        val instances = AtomicInteger()
    }
}
