package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Reproduces the MCP Java SDK stdio race: concurrent tool replies make the
 * unicast outbound sink's tryEmitNext fail ("Failed to enqueue message"), and
 * the reply is dropped. Kiro CLI V3 issues parallel tool calls, so this hangs
 * the client until it times out and closes the transport.
 */
class SerializedStdioServerTransportProviderTest {

    private static final int SENDERS = 32;
    private static final int PER_SENDER = 25;

    private final McpJsonMapper mapper = new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());

    /** Runs concurrent sends through the provider and returns how many lines reached stdout. */
    private long concurrentSends(Function<StdioServerTransportProvider, McpServerTransportProvider> wrap) throws Exception {
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientToServer);
        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();

        StdioServerTransportProvider stdio = new StdioServerTransportProvider(mapper, serverIn, serverOut);
        McpServerTransportProvider provider = wrap.apply(stdio);

        AtomicReference<McpServerTransport> transportRef = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        provider.setSessionFactory(transport -> {
            transportRef.set(transport);
            ready.countDown();
            return new McpServerSession("test", Duration.ofSeconds(5), transport,
                    init -> Mono.empty(), java.util.Map.of(), java.util.Map.of());
        });
        ready.await(5, TimeUnit.SECONDS);
        McpServerTransport transport = transportRef.get();

        // Any inbound line starts the outbound writer in the SDK transport.
        clientToServer.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n".getBytes(StandardCharsets.UTF_8));
        clientToServer.flush();
        Thread.sleep(300);

        ExecutorService pool = Executors.newFixedThreadPool(SENDERS);
        CountDownLatch start = new CountDownLatch(1);
        for (int s = 0; s < SENDERS; s++) {
            final int sender = s;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < PER_SENDER; i++) {
                    McpSchema.JSONRPCResponse msg = new McpSchema.JSONRPCResponse(
                            McpSchema.JSONRPC_VERSION, sender * 1000 + i, java.util.Map.of("ok", true), null);
                    try {
                        transport.sendMessage(msg).block(Duration.ofSeconds(5));
                    } catch (RuntimeException ignored) {
                        // stock transport: "Failed to enqueue message" -> reply lost
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        Thread.sleep(500);
        provider.closeGracefully().block(Duration.ofSeconds(5));
        clientToServer.close();

        return serverOut.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).count();
    }

    @Test
    void stockProviderDropsRepliesUnderConcurrency() throws Exception {
        long delivered = concurrentSends(p -> p);
        org.junit.jupiter.api.Assertions.assertTrue(delivered < (long) SENDERS * PER_SENDER,
                "expected the unpatched SDK transport to drop replies; delivered " + delivered
                        + " of " + SENDERS * PER_SENDER + " (if this fails, the SDK bug may be fixed upstream)");
    }

    @Test
    void serializedProviderDeliversEveryConcurrentReply() throws Exception {
        long delivered = concurrentSends(SerializedStdioServerTransportProvider::new);
        assertEquals((long) SENDERS * PER_SENDER, delivered, "every concurrent reply must reach stdout");
    }
}
