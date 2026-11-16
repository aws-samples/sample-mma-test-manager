package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {

    @Bean
    public Db2LuwMcpTools db2LuwMcpTools() {
        return new Db2LuwMcpTools();
    }

    /**
     * Replaces Spring AI's default stdio transport (created only when no
     * McpServerTransportProviderBase bean exists) with one that serialises
     * replies, so parallel tool calls from the client are never dropped.
     * See SerializedStdioServerTransportProvider.
     */
    @Bean
    public McpServerTransportProviderBase stdioServerTransport(
            @Qualifier("mcpServerObjectMapper") ObjectMapper mcpServerObjectMapper) {
        return new SerializedStdioServerTransportProvider(
                new StdioServerTransportProvider(new JacksonMcpJsonMapper(mcpServerObjectMapper)));
    }
}
