package com.example;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {

    @Bean
    public SybaseMcpTools sybaseMcpTools() {
        return new SybaseMcpTools();
    }
}
