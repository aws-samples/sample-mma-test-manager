package com.mma.testmanager.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.sql.DataSource;

@Configuration
@Slf4j
public class DataSourceConfig {

    @Value("${mma.repo.type:direct}")
    private String repoType;

    @Value("${mma.repo.secret:}")
    private String repoSecret;

    @Bean
    @Primary
    @ConditionalOnProperty(name = "mma.repo.type", havingValue = "secretsmanager")
    public DataSource dataSourceFromSecretsManager() {
        log.info("Configuring datasource from Secrets Manager: {}", repoSecret);
        
        try (SecretsManagerClient client = SecretsManagerClient.builder().build()) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(repoSecret)
                .build();
            
            String secretString = client.getSecretValue(request).secretString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(secretString);
            
            String host = json.path("host").asText();
            String port = json.path("port").asText();
            String dbname = json.path("dbname").asText();
            String username = json.path("username").asText();
            String password = json.path("password").asText();
            
            String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, dbname);
            
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(jdbcUrl);
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            dataSource.setDriverClassName("org.postgresql.Driver");
            
            log.info("Datasource configured successfully: {}", jdbcUrl);
            return dataSource;
        } catch (Exception e) {
            log.error("Failed to configure datasource from Secrets Manager", e);
            throw new RuntimeException("Failed to configure datasource", e);
        }
    }
    
    @Bean
    @Primary
    @ConditionalOnProperty(name = "mma.repo.type", havingValue = "password")
    public DataSource dataSourceFromPassword() {
        log.info("Configuring datasource from password credentials");
        
        // Format: username:password@hostname:port/database
        String[] parts = repoSecret.split("@");
        String[] userPass = parts[0].split(":");
        String[] hostPortDb = parts[1].split("/");
        String[] hostPort = hostPortDb[0].split(":");
        
        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", hostPort[0], hostPort[1], hostPortDb[1]);
        
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(userPass[0]);
        dataSource.setPassword(userPass[1]);
        dataSource.setDriverClassName("org.postgresql.Driver");
        
        log.info("Datasource configured successfully: {}", jdbcUrl);
        return dataSource;
    }
}
