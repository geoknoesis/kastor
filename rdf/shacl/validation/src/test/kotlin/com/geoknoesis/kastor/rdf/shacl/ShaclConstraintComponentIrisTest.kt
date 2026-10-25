package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShaclConstraintComponentIrisTest {
    /** The table replaces an exhaustive `when`: a new [ConstraintType] must be given a component or listed as having none. */
    @Test fun `every constraint type has a standard component or is listed as having none`() {
        for (type in ConstraintType.entries) {
            val iri = type.toSourceConstraintComponentIri()
            if (type in constraintTypesWithoutSourceComponent) {
                assertNull(iri, "$type")
            } else {
                assertNotNull(iri, "$type has no sh:sourceConstraintComponent; list it in constraintTypesWithoutSourceComponent if it has none")
                assertTrue(iri!!.value.startsWith(SHACL.namespace) && iri.value.endsWith("ConstraintComponent"), "$type -> $iri")
            }
        }
        assertEquals(SHACL.SPARQLConstraintComponent, ConstraintType.SPARQL_CONSTRAINT_COMPONENT.toSourceConstraintComponentIri())
    }
}
