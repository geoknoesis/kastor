package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapes
import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesBounds
import com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value

/** The admission of shapes embedded in the data graph, which are untrusted (see `Rdf4jValidation`). */
@OptIn(KastorGenInternalApi::class)
internal object Rdf4jEmbeddedShapes {
    /**
     * Throws `EmbeddedShapesRefusedException` when [shapes] (the statements loaded into ShaclSail's shapes graph) use
     * a term outside `EmbeddedShapes.ALLOWED`, or exceed the structural caps of `EmbeddedShapesBounds`: ShaclSail
     * builds a validation plan of their unfolded size before it reads any data.
     */
    fun requireAdmissible(shapes: List<Statement>) {
        EmbeddedShapes.requireNone(EmbeddedShapes.refused(shapes.asSequence().map { it.predicate.stringValue() }.iterator()), "Rdf4jValidation")
        EmbeddedShapesBounds.requireWithin(shapes.asSequence().map { Triple(key(it.subject), it.predicate.stringValue(), key(it.`object`)) }, "Rdf4jValidation")
    }

    /** The node key of `EmbeddedShapesBounds`: no IRI starts with a quote or a space. */
    private fun key(value: Value): String = when (value) {
        is IRI -> value.stringValue()
        is BNode -> "_:" + value.id
        is Literal -> "\"" + value.label
        else -> " $value"
    }
}
