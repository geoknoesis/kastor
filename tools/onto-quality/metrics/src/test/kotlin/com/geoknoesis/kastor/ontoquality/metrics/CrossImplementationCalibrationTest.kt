package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Calibrates OQuaRE raw values against expectations derived **by hand** from the formulas documented on
 * `OquareCalculators` (Duque-Ramos et al. 2014, PLoS ONE 9(8), as operationalised by
 * [tecnomod-um/oquare-metrics](https://github.com/tecnomod-um/oquare-metrics)).
 *
 * Notation per fixture: C = named classes, Root = classes without a named superclass, Sub = asserted
 * named-to-named rdfs:subClassOf edges, P_C = object/datatype properties used by C (rdfs:domain or
 * owl:Restriction on C).
 */
class CrossImplementationCalibrationTest {
    private fun metrics(resource: String): OquareMetrics {
        val ttl = checkNotNull(javaClass.getResourceAsStream(resource)).bufferedReader().readText()
        return VocabularyMetrics.compute(Rdf.parse(ttl, "TURTLE")).owl.oquare
    }

    private fun assertRaw(expected: Map<String, Double>, oq: OquareMetrics) {
        val actual = oq.toList().associate { it.oquareName to it.rawValue }
        for ((name, value) in expected) {
            assertEquals(value, actual.getValue(name), TOLERANCE, "$name (all values: $actual)")
        }
    }

    /**
     * C={A,B}, Root={A}, Sub={B⊑A}, leaves={B}, P_B={P} (domain), P declared as object property.
     */
    @Test
    fun `minimal-branches fixture matches hand-derived OQuaRE values`() {
        val oq = metrics("/cross-impl/minimal-branches.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 1.0, // A -> B
                "NACOnto" to 1.0, // |Sup(B)| / |leaves| = 1/1
                "NOCOnto" to 1.0, // |Sub| / (|C| - |Root|) = 1/1
                "CBOOnto" to 1.0, // sum|Sup_C| / (|C| - |Root|) = 1/1
                "WMCOnto" to 1.0, // sum(|P_C| + |Sub_C|) / |C| = (0+1 + 1+0)/2
                "RFCOnto" to 2.0, // sum(|P_C| + |Sup_C|) / (|C| - |Root|) = (0+0 + 1+1)/1
                "NOMOnto" to 0.5, // sum|P_C| / |C| = 1/2
                "LCOMOnto" to 1.0, // path lengths / paths = 1/1
                "RROnto" to 0.5, // |P usages| / (|Sub| + |P usages|) = 1/(1+1)
                "INROnto" to 0.5, // |Sub| / |C| = 1/2
                "AROnto" to 0.0, // datatype attributes / |C|
                "CROnto" to 0.0, // instances / |C|
                "ANOnto" to 0.0, // annotations / |C|
                "PROnto" to 0.5, // |declared obj+dt properties| / (|Sub| + that) = 1/(1+1)
                "TMOnto" to 0.0, // no class with more than one direct parent
            ),
            oq,
        )
    }

    /**
     * C={Animal,Dog,Cat,Owner}, Root={Animal,Owner}, Sub={Dog⊑Animal, Cat⊑Animal}, leaves={Dog,Cat,Owner},
     * P_Animal={name} (domain), P_Owner={owns} (domain and restriction — counted once),
     * 3 instances, 3 annotations on classes (Animal label+comment, Dog label).
     */
    @Test
    fun `populated fixture with restrictions matches hand-derived OQuaRE values`() {
        val oq = metrics("/cross-impl/populated-restrictions.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 1.0,
                "NACOnto" to 2.0 / 3.0, // (1 + 1 + 0) / 3 leaves
                "NOCOnto" to 1.0, // 2 / (4 - 2)
                "CBOOnto" to 1.0, // 2 / (4 - 2)
                "WMCOnto" to 1.0, // Animal(1+2) + Dog(0) + Cat(0) + Owner(1+0) = 4 / 4
                "RFCOnto" to 2.0, // Animal(1+0) + Dog(0+1) + Cat(0+1) + Owner(1+0) = 4 / 2
                "NOMOnto" to 0.5, // 2 / 4
                "LCOMOnto" to 2.0 / 3.0, // paths Animal-Dog(1), Animal-Cat(1), Owner(0) -> 2 / 3
                "RROnto" to 0.5, // 2 / (2 + 2)
                "INROnto" to 0.5, // 2 / 4
                "AROnto" to 0.25, // 1 datatype attribute / 4
                "CROnto" to 0.75, // 3 instances / 4
                "ANOnto" to 0.75, // 3 annotations / 4
                "PROnto" to 0.5, // 2 declared / (2 + 2)
                "TMOnto" to 0.0,
            ),
            oq,
        )
    }

    /** Diamond: A ⊒ B, C ⊒ D. Two root-to-leaf paths of length 2; D has two direct parents. */
    @Test
    fun `diamond fixture matches hand-derived OQuaRE values`() {
        val oq = metrics("/fixtures/diamond.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 2.0,
                "NACOnto" to 2.0,
                "NOCOnto" to 4.0 / 3.0, // 4 edges / (4 - 1)
                "CBOOnto" to 4.0 / 3.0,
                "RFCOnto" to 4.0 / 3.0, // no properties; 4 superclass links / 3
                "LCOMOnto" to 2.0, // (2 + 2) / 2 paths
                "INROnto" to 1.0, // 4 / 4
                "RROnto" to 0.0, // no property usages
                "TMOnto" to 2.0, // D: 2 parents
            ),
            oq,
        )
        assertFalse(oq.classRichness.notes.isNullOrBlank())
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
