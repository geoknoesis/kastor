package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.LiteralValidation
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.sail.NotifyingSailConnection
import org.eclipse.rdf4j.sail.SailException
import org.eclipse.rdf4j.sail.UpdateContext
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.memory.MemoryStoreConnection
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import org.eclipse.rdf4j.sail.nativerdf.NativeStoreConnection

/**
 * The stores Kastor creates refuse, on every write, a numeric or temporal literal longer than
 * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH]: the literals a SPARQL UPDATE computes (`STRDT(CONCAT(...),
 * xsd:integer)`, `xsd:integer(REPLACE(...))`, a cast), those it copies with `INSERT ... WHERE`, those `LOAD` reads, and
 * those of the graph API and of inference. RDF4J's MemoryStore turns an integer or decimal into a `BigInteger` when it
 * stores it, in time quadratic in its digits (two 1,000,000-digit integers: 33 s); the text checks of an update
 * ([Rdf4jLiteralChecks.requireBoundedUpdate]) only see the literals written in it. The write fails with
 * [SailException] (an update reports it as `RdfQueryException`), with the parse path's message.
 */
internal object Rdf4jBoundedWrites {

    /** Fails with [SailException] when [value] (or a term of a triple term) is such a literal. */
    fun requireBounded(value: Value) {
        when (value) {
            is Literal -> LiteralValidation.numericLengthProblem(value.datatype.stringValue(), value.label)?.let { throw SailException(it) }
            is Triple -> {
                requireBounded(value.subject)
                requireBounded(value.`object`)
            }
            else -> Unit
        }
    }

    fun requireBounded(subj: Resource, obj: Value) {
        if (subj is Triple) requireBounded(subj)
        requireBounded(obj)
    }
}

/** A [MemoryStore] whose connections check every statement they write ([Rdf4jBoundedWrites]). */
internal class BoundedMemoryStore : MemoryStore() {
    override fun getConnectionInternal(): NotifyingSailConnection = object : MemoryStoreConnection(this) {
        override fun addStatementInternal(subj: Resource, pred: IRI, obj: Value, vararg contexts: Resource?) {
            Rdf4jBoundedWrites.requireBounded(subj, obj)
            super.addStatementInternal(subj, pred, obj, *contexts)
        }

        override fun addStatement(op: UpdateContext?, subj: Resource, pred: IRI, obj: Value, vararg contexts: Resource?) {
            Rdf4jBoundedWrites.requireBounded(subj, obj)
            super.addStatement(op, subj, pred, obj, *contexts)
        }

        override fun addInferredStatement(subj: Resource, pred: IRI, obj: Value, vararg contexts: Resource?): Boolean {
            Rdf4jBoundedWrites.requireBounded(subj, obj)
            return super.addInferredStatement(subj, pred, obj, *contexts)
        }
    }
}

/** A [NativeStore] whose connections check every statement they write ([Rdf4jBoundedWrites]). */
internal class BoundedNativeStore(dataDir: java.io.File) : NativeStore(dataDir) {
    override fun getConnectionInternal(): NotifyingSailConnection = object : NativeStoreConnection(this) {
        override fun addStatementInternal(subj: Resource, pred: IRI, obj: Value, vararg contexts: Resource?) {
            Rdf4jBoundedWrites.requireBounded(subj, obj)
            super.addStatementInternal(subj, pred, obj, *contexts)
        }

        override fun addStatement(op: UpdateContext?, subj: Resource, pred: IRI, obj: Value, vararg contexts: Resource?) {
            Rdf4jBoundedWrites.requireBounded(subj, obj)
            super.addStatement(op, subj, pred, obj, *contexts)
        }

        override fun addInferredStatement(subj: Resource, pred: IRI, obj: Value, vararg contexts: Resource?): Boolean {
            Rdf4jBoundedWrites.requireBounded(subj, obj)
            return super.addInferredStatement(subj, pred, obj, *contexts)
        }
    }
}
