package com.geoknoesis.kastor.rdf.rdf4j

/** Whether the patterns of a query (or of the `WHERE` clause of an update) read outside and inside `GRAPH`. */
internal class GraphReads(val default: Boolean, val named: Boolean) {
    companion object {
        fun of(expr: org.eclipse.rdf4j.query.algebra.TupleExpr?): GraphReads {
            var readsDefault = false
            var readsNamed = false
            fun note(scope: org.eclipse.rdf4j.query.algebra.StatementPattern.Scope) {
                if (scope == org.eclipse.rdf4j.query.algebra.StatementPattern.Scope.NAMED_CONTEXTS) readsNamed = true else readsDefault = true
            }
            expr?.visit(object : org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor<RuntimeException>() {
                override fun meet(node: org.eclipse.rdf4j.query.algebra.StatementPattern) { note(node.scope); super.meet(node) }
                override fun meet(node: org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath) { note(node.scope); super.meet(node) }
                override fun meet(node: org.eclipse.rdf4j.query.algebra.ZeroLengthPath) { note(node.scope); super.meet(node) }
                override fun meet(node: org.eclipse.rdf4j.query.algebra.DescribeOperator) { readsDefault = true; super.meet(node) }
                // The patterns of a SERVICE clause are evaluated by the remote endpoint against its own dataset.
                override fun meet(node: org.eclipse.rdf4j.query.algebra.Service) = Unit
            })
            return GraphReads(readsDefault, readsNamed)
        }
    }
}
