package com.example;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The server must use the serialising transport, not Spring AI's default stdio one. */
class McpTransportWiringTest {

    @Test
    void stdioTransportIsTheSerialisedOne() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        McpServerObjectMapperAutoConfiguration.class, McpServerAutoConfiguration.class))
                .withUserConfiguration(McpConfiguration.class)
                // The tool beans autowire JDBC; stubs suffice to build the transport.
                .withBean(javax.sql.DataSource.class, () -> org.mockito.Mockito.mock(javax.sql.DataSource.class))
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withPropertyValues("spring.ai.mcp.server.stdio=true", "spring.ai.mcp.server.transport=stdio")
                .run(ctx -> assertInstanceOf(SerializedStdioServerTransportProvider.class,
                        ctx.getBean(McpServerTransportProviderBase.class)));
    }
}
