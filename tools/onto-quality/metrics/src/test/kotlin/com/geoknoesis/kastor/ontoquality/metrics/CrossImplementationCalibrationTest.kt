package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Calibrates OQuaRE raw values against expectations derived **by hand** from the OQuaRE metric definitions
 * (Duque-Ramos et al. 2014, PLoS ONE 9(8), as operationalised by
 * [tecnomod-um/oquare-metrics](https://github.com/tecnomod-um/oquare-metrics)), with the Kastor
 * interpretations documented in the module README. Every expected number below is worked out in the comments
 * from the fixture triples; none was obtained by running the calculators.
 *
 * Notation per fixture: C = named classes, Root = classes whose only superclass is owl:Thing,
 * Sub_C / Sup_C = direct named subclasses / superclasses, P_C = object/datatype properties used by C
 * (rdfs:domain or owl:Restriction on C), Assoc_C = named classes C is associated with through a property
 * (range of a property whose domain is C, or the filler of a restriction on C), depth(Root) = 1 (owl:Thing
 * is depth 0) and paths run from owl:Thing to a leaf.
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
     * C={A,B}, Root={A}, Sub={B⊑A}, leaves={B}, P_B={P} (domain), P declared as object property with range A.
     */
    @Test
    fun `minimal-branches fixture matches hand-derived OQuaRE values`() {
        val oq = metrics("/cross-impl/minimal-branches.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 2.0, // Thing -> A (1) -> B (2)
                "NACOnto" to 1.0, // |Sup(B)| / |leaves| = 1/1
                "NOCOnto" to 1.0, // sum|Sub_C| / |{C : Sub_C non-empty}| = 1 / |{A}|
                "CBOOnto" to 0.5, // sum|Sup_C ∪ Assoc_C| / |C| = (A: 0 + B: {A}) / 2
                "WMCOnto" to 1.0, // sum(|P_C| + |Sub_C|) / |C| = (0+1 + 1+0)/2
                "RFCOnto" to 2.0, // sum(|P_C| + |Sup_C|) / (|C| - |Root|) = (0+0 + 1+1)/1
                "NOMOnto" to 0.5, // sum|P_C| / |C| = 1/2
                "LCOMOnto" to 2.0, // one path Thing-A-B of length 2
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
     * P_Animal={name} (domain, no class range), P_Owner={owns} (domain with range Animal, and restriction
     * someValuesFrom Animal — counted once), 3 instances, 3 annotations on classes (Animal label+comment, Dog label).
     */
    @Test
    fun `populated fixture with restrictions matches hand-derived OQuaRE values`() {
        val oq = metrics("/cross-impl/populated-restrictions.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 2.0, // Thing -> Animal (1) -> Dog/Cat (2); Owner is 1
                "NACOnto" to 2.0 / 3.0, // (1 + 1 + 0) / 3 leaves
                "NOCOnto" to 2.0, // Animal is the only class with subclasses: 2 / 1
                "CBOOnto" to 0.75, // Animal 0 + Dog {Animal} + Cat {Animal} + Owner {Animal} = 3 / 4
                "WMCOnto" to 1.0, // Animal(1+2) + Dog(0) + Cat(0) + Owner(1+0) = 4 / 4
                "RFCOnto" to 2.0, // Animal(1+0) + Dog(0+1) + Cat(0+1) + Owner(1+0) = 4 / 2
                "NOMOnto" to 0.5, // 2 / 4
                "LCOMOnto" to 5.0 / 3.0, // paths Thing-Animal-Dog(2), Thing-Animal-Cat(2), Thing-Owner(1) -> 5 / 3
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

    /** Diamond: A ⊒ B, C ⊒ D. Two Thing-to-leaf paths of length 3; D has two direct parents. */
    @Test
    fun `diamond fixture matches hand-derived OQuaRE values`() {
        val oq = metrics("/fixtures/diamond.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 3.0, // Thing -> A (1) -> B/C (2) -> D (3)
                "NACOnto" to 2.0,
                "NOCOnto" to 4.0 / 3.0, // A has 2, B has 1, C has 1 subclasses: 4 / 3
                "CBOOnto" to 1.0, // A 0 + B {A} + C {A} + D {B, C} = 4 / 4
                "RFCOnto" to 4.0 / 3.0, // no properties; 4 superclass links / 3
                "LCOMOnto" to 3.0, // (3 + 3) / 2 paths
                "INROnto" to 1.0, // 4 / 4
                "RROnto" to 0.0, // no property usages
                "TMOnto" to 2.0, // D: 2 parents
            ),
            oq,
        )
        // A diamond must score worse than a hierarchy without multiple inheritance (5).
        assertEquals(4, oq.tangledness.score)
        assertFalse(oq.classRichness.notes.isNullOrBlank())
    }

    /**
     * Externally derived fixture (`cross-impl/pizza-like.ttl`), worked out by hand from the OQuaRE definitions.
     *
     * - C (10): Food, Pizza, PizzaBase, PizzaTopping, ThinBase, CheeseTopping, VegetableTopping,
     *   CheesyVegetableTopping, Margherita, Spiciness. Root (2): Food, Spiciness.
     * - Named subclass edges (9): Pizza, PizzaBase, PizzaTopping ⊑ Food; ThinBase ⊑ PizzaBase;
     *   CheeseTopping, VegetableTopping ⊑ PizzaTopping; CheesyVegetableTopping ⊑ CheeseTopping, VegetableTopping;
     *   Margherita ⊑ Pizza.
     * - Classes with subclasses (6): Food 3, PizzaBase 1, PizzaTopping 2, CheeseTopping 1, VegetableTopping 1, Pizza 1.
     * - Leaves (4): ThinBase, CheesyVegetableTopping, Margherita, Spiciness.
     * - P_Pizza = {hasTopping, hasBase, hasCalories} (domains), P_Margherita = {hasTopping} (restriction): Σ|P_C| = 4.
     *   Declared object + datatype properties: 3. Datatype domain assertions on classes: 1 (hasCalories).
     * - Depths: Food 1, Spiciness 1, Pizza/PizzaBase/PizzaTopping 2, ThinBase/CheeseTopping/VegetableTopping/Margherita 3,
     *   CheesyVegetableTopping 4.
     * - Thing-to-leaf paths (5): ThinBase 3, CheesyVegetableTopping 4 (via Cheese) and 4 (via Vegetable),
     *   Margherita 3, Spiciness 1 — total length 15.
     * - Sup ∪ Assoc: Pizza {Food, PizzaTopping, PizzaBase} (hasCalories has a datatype range), Margherita
     *   {Pizza, CheeseTopping}, CheesyVegetableTopping {CheeseTopping, VegetableTopping}, PizzaBase/PizzaTopping {Food},
     *   ThinBase {PizzaBase}, CheeseTopping/VegetableTopping {PizzaTopping}, Food and Spiciness none: Σ = 12.
     * - Annotations on classes: Food label, Pizza label + comment = 3. Instances: myMargherita = 1.
     */
    @Test
    fun `pizza-like fixture matches values derived by hand from the OQuaRE definitions`() {
        val oq = metrics("/cross-impl/pizza-like.ttl")
        assertRaw(
            mapOf(
                "DITOnto" to 4.0, // CheesyVegetableTopping at depth 4
                "NACOnto" to 1.0, // (ThinBase 1 + CheesyVegetableTopping 2 + Margherita 1 + Spiciness 0) / 4
                "NOCOnto" to 1.5, // 9 / 6
                "CBOOnto" to 1.2, // 12 / 10
                "WMCOnto" to 1.3, // (Σ|P_C| 4 + Σ|Sub_C| 9) / 10
                "RFCOnto" to 13.0 / 8.0, // (Σ|P_C| 4 + Σ|Sup_C| 9) / (10 - 2)
                "NOMOnto" to 0.4, // 4 / 10
                "LCOMOnto" to 3.0, // 15 / 5
                "RROnto" to 4.0 / 13.0, // 4 / (9 + 4)
                "INROnto" to 0.9, // 9 / 10
                "AROnto" to 0.1, // 1 / 10
                "CROnto" to 0.1, // 1 / 10
                "ANOnto" to 0.3, // 3 / 10
                "PROnto" to 0.25, // 3 / (9 + 3)
                "TMOnto" to 2.0, // CheesyVegetableTopping: 2 parents / 1 tangled class
            ),
            oq,
        )
        // Scores from the OQuaRE bands: DIT 4 in (2, 4] -> 4; NOC 1.5 <= 3 -> 5; RR 30.8% in (20, 40%] -> 2;
        // INR 0.9 > 0.8 -> 5; TM 2 (tangled) -> 4.
        assertEquals(4, oq.depthOfInheritanceTree.score)
        assertEquals(5, oq.numberOfChildren.score)
        assertEquals(2, oq.relationshipRichness.score)
        assertEquals(5, oq.inheritanceRichness.score)
        assertEquals(4, oq.tangledness.score)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
