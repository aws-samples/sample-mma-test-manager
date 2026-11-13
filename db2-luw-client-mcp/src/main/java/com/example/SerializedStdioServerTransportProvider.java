package com.example;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Wraps the MCP SDK's {@code StdioServerTransportProvider} so that replies are
 * handed to its outbound sink one at a time.
 *
 * <p>The SDK (mcp-core 0.18.x) enqueues each reply with
 * {@code Sinks.many().unicast()...tryEmitNext()}. That sink is not safe for
 * concurrent producers: when two tool calls finish at the same moment, the
 * second {@code tryEmitNext} returns {@code FAIL_NON_SERIALIZED}, the SDK throws
 * "Failed to enqueue message", and the reply is silently dropped. Kiro CLI V3
 * issues tool calls in parallel, so it then waits on the missing reply until it
 * times out and closes the transport.
 *
 * <p>Serialising {@code sendMessage} per session removes the race without
 * changing message order guarantees or touching the SDK. Spring AI only creates
 * its default stdio transport when no {@code McpServerTransportProviderBase}
 * bean exists, so registering this one replaces it.
 */
public class SerializedStdioServerTransportProvider implements McpServerTransportProvider {

    private final McpServerTransportProvider delegate;

    public SerializedStdioServerTransportProvider(McpServerTransportProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(transport -> sessionFactory.create(new SerializedTransport(transport)));
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    /** Session transport whose sendMessage subscriptions never overlap. */
    static final class SerializedTransport implements McpServerTransport {

        private final McpServerTransport delegate;
        private final Object sendLock = new Object();

        SerializedTransport(McpServerTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            // The SDK's sendMessage is a Mono.defer around tryEmitNext, so the
            // emission happens on subscribe. Subscribing and completing it under
            // the lock keeps emissions strictly sequential. Enqueueing is cheap;
            // the actual stdout write happens later on the SDK's writer thread.
            return Mono.defer(() -> {
                synchronized (sendLock) {
                    try {
                        delegate.sendMessage(message).block();
                        return Mono.empty();
                    } catch (RuntimeException e) {
                        return Mono.error(e);
                    }
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }
}
