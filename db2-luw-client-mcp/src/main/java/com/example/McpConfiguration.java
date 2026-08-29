package com.example;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {

    @Bean
    public Db2LuwMcpTools db2LuwMcpTools() {
        return new Db2LuwMcpTools();
    }
}
