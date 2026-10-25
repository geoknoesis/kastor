package com.geoknoesis.kastor.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import kotlin.Unit;
import org.junit.jupiter.api.Test;

/**
 * The core entry points are usable from Java: this file must compile. Each {@code catch} of a checked Kastor exception
 * compiles only because the called function declares it ({@code @Throws}), and the static calls only because
 * {@code Rdf} exposes {@code @JvmStatic} members with {@code @JvmOverloads} defaults.
 */
class CoreJavaApiTest {
    private static final String TURTLE = "<http://example.org/s> <http://example.org/p> \"o\" .";

    @Test
    void parseIsStaticWithDefaultFormatAndFailuresAreCaughtByType() throws RdfException {
        try {
            assertEquals(1, Rdf.parse(TURTLE).size());
            Rdf.parse("<http://example.org/s> <http://example.org/p> .");
            fail("A malformed document must not parse");
        } catch (RdfFormatException e) {
            assertEquals(RdfErrorCode.FORMAT_PARSE_ERROR, e.getErrorCode());
        }
    }

    @Test
    void anUnknownFormatIsAnRdfException() {
        try {
            Rdf.parse(TURTLE, "no-such-format");
            fail("An unknown format must be rejected");
        } catch (RdfException e) {
            assertTrue(e instanceof RdfFormatException);
        }
    }

    @Test
    void streamParsingDeclaresBothRdfAndIoFailures() {
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(TURTLE.getBytes(StandardCharsets.UTF_8));
            assertEquals(1, Rdf.parseFromInputStream(input, "TURTLE").size());
        } catch (RdfException | IOException e) {
            fail(e);
        }
    }

    @Test
    void aRefusedUrlIsCaughtByTypeWithoutNetworkAccess() {
        String url = "http://127.0.0.1:9/data.ttl";
        try {
            Rdf.parseFromUrl(url);
            fail("The default address policy must refuse loopback");
        } catch (RdfAddressRefusedException e) {
            assertEquals(url, e.getUrl());
        } catch (RdfException | IOException e) {
            fail(e);
        }
    }

    @Test
    void anAsyncUrlLoadCompletesWithTheSameException() throws InterruptedException {
        CompletableFuture<MutableRdfGraph> future = Rdf.parseFromUrlAsync("http://127.0.0.1:9/data.ttl");
        try {
            future.get();
            fail("The default address policy must refuse loopback");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof RdfAddressRefusedException, String.valueOf(e.getCause()));
        }
    }

    @Test
    void urlLoadOptionsHaveJavaConstructorsAndWithers() {
        UrlLoadOptions https = new UrlLoadOptions(Collections.singleton("https"));
        assertEquals(UrlLoadOptions.DEFAULT_MAX_BYTES, https.getMaxBytes());
        assertSame(UrlAddressPolicy.PUBLIC_ADDRESSES, https.getAddressPolicy());
        UrlLoadOptions small = UrlLoadOptions.DEFAULT.withMaxBytes(1024L).withTotalTimeoutMillis(2_000L);
        assertEquals(1024L, small.getMaxBytes());
        assertEquals(2_000L, small.getTotalTimeoutMillis());
        assertEquals(UrlLoadOptions.DEFAULT.getAllowedSchemes(), small.getAllowedSchemes());
        UrlLoadOptions local = UrlLoadOptions.DEFAULT.withAddressPolicy(UrlAddressPolicy.ALLOW_ALL)
            .withRedirectPolicy(UrlRedirectPolicy.SAME_HOST)
            .withAllowedSchemes(Collections.singleton("file"))
            .withConnectTimeoutMillis(10)
            .withReadTimeoutMillis(20);
        assertEquals(UrlLoadOptions.ALLOW_ALL_ADDRESSES.getAddressPolicy(), local.getAddressPolicy());
        assertEquals(UrlRedirectPolicy.SAME_HOST, local.getRedirectPolicy());
        assertEquals(10, local.getConnectTimeoutMillis());
        assertEquals(20, local.getReadTimeoutMillis());
    }

    @Test
    void repositoryQueriesAndTransactionsDeclareRdfException() {
        try (RdfRepository repository = Rdf.memory()) {
            repository.transaction(repo -> Unit.INSTANCE);
            assertFalse(repository.select(new SandboxedSelectQuery("SELECT * WHERE { ?s ?p ?o }")).iterator().hasNext());
            repository.select(new SandboxedSelectQuery("SELECT * WHERE { ?s ?p"));
            fail("A malformed query must not run");
        } catch (RdfException e) {
            assertNotNull(e.getErrorCode());
        }
    }
}
