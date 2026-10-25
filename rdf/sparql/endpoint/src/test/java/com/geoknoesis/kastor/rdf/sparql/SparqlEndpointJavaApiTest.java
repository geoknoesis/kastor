package com.geoknoesis.kastor.rdf.sparql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The endpoint API is usable from Java with the same defaults as from Kotlin: this file must compile. */
class SparqlEndpointJavaApiTest {
    private static final String URL = "http://127.0.0.1:9/sparql";

    @Test
    void configurationTakesItsDefaultsFromJava() {
        SparqlEndpointConfig config = new SparqlEndpointConfig(URL);
        assertEquals(new SparqlEndpointConfig(URL, null), config);
        assertEquals(SparqlEndpointConfig.DEFAULT_MAX_RESPONSE_BYTES, config.getMaxResponseBytes());
        SparqlEndpointConfig withUpdate = new SparqlEndpointConfig(URL, "http://127.0.0.1:9/update", 1024L);
        assertEquals(1024L, withUpdate.getMaxResponseBytes());
        assertEquals(config.getReadTimeout(), withUpdate.getReadTimeout());
    }

    @Test
    void repositoryConvenienceConstructorsFromJava() {
        try (SparqlRepository repository = new SparqlRepository(URL)) {
            assertEquals(Duration.ofMillis(SparqlEndpointConfig.DEFAULT_READ_TIMEOUT_MILLIS), repository.getConfig().getReadTimeout());
        }
        try (SparqlRepository repository = new SparqlRepository(URL, 2048L)) {
            assertEquals(2048L, repository.getConfig().getMaxResponseBytes());
        }
    }

    @Test
    void providerVersionIsTheModuleVersion() {
        String version = new SparqlProvider().getVersion();
        assertNotEquals("1.0.0", version);
        Package pkg = SparqlProvider.class.getPackage();
        String manifest = pkg == null ? null : pkg.getImplementationVersion();
        assertEquals(manifest == null ? "development" : manifest, version);
        assertTrue(!version.isEmpty());
    }
}
