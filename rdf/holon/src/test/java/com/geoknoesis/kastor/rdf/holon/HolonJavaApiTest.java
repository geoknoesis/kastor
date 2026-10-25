package com.geoknoesis.kastor.rdf.holon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.geoknoesis.kastor.rdf.Dataset;
import com.geoknoesis.kastor.rdf.Rdf;
import com.geoknoesis.kastor.rdf.RdfException;
import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.RdfResource;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * The holon API is usable from Java: this file must compile. {@code HolonException} is a checked exception (an
 * {@code RdfException}), so each {@code catch} below compiles only because the called function declares it.
 */
class HolonJavaApiTest {
    private static final String METADATA = "<https://example.org/A> a <https://w3id.org/rdf-h#Holon> .\n"
        + "<https://example.org/A> <https://example.org/value> 1 .";

    private static RdfGraph metadata() throws RdfException {
        return Rdf.parse(METADATA);
    }

    private static RdfResource subject(RdfGraph graph) {
        return graph.getTriples().get(0).getSubject();
    }

    @Test
    void anUnknownHolonIsCaughtByType() throws RdfException {
        HolonContext context = HolonContext.of(metadata());
        RdfResource unknown = subject(Rdf.parse("<https://example.org/Unknown> <https://example.org/p> 1 ."));
        try {
            context.resolve(unknown);
            fail("An unregistered holon must not resolve");
        } catch (HolonException e) {
            assertEquals(HolonError.UNKNOWN_HOLON, e.getCondition());
        }
        try {
            HolonKt.holon(context, unknown);
            fail("An unregistered holon must not be selected");
        } catch (HolonException e) {
            assertEquals(HolonError.UNKNOWN_HOLON, e.getCondition());
        }
    }

    @Test
    void holonOperationsTakeTheirDefaultsFromJava() throws RdfException {
        RdfGraph m = metadata();
        HolonContext context = HolonContext.of(m, Collections.emptyMap());
        Holon holon = HolonKt.holon(context, subject(m));
        assertEquals(HolonScope.DIRECT, holon.resolve().getScope());
        assertEquals(HolonScope.AGGREGATED, context.resolve(subject(m), HolonScope.AGGREGATED).getScope());
        try (Dataset dataset = holon.asDataset()) {
            assertEquals(0, dataset.getDefaultGraph().size());
        }
        try {
            holon.asDataset(HolonScope.GLOBAL);
            fail("GLOBAL is not a query scope");
        } catch (HolonException e) {
            assertEquals(HolonError.UNSUPPORTED_CAPABILITY, e.getCondition());
        }
        try {
            holon.convert(HolonProfile.MIXED);
            fail("MIXED is not a conversion target");
        } catch (HolonException e) {
            assertEquals(HolonError.UNSUPPORTED_CAPABILITY, e.getCondition());
        }
        HolonContext built = HolonDslKt.holons();
        assertEquals(0, built.getRegistry().size());
    }
}
