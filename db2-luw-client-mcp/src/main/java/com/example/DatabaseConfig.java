package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.regions.Region;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    private static final String DRIVER_CLASS = "com.ibm.db2.jcc.DB2Driver";
    private static final String DEFAULT_DATABASE = "SAMPLE";

    @Value("${mcp.db.connection.type:password}")
    private String connectionType;

    @Value("${mcp.db.connection.detail}")
    private String connectionDetail;

    /**
     * Optional default schema (DB2 CURRENT SCHEMA). When empty, DB2 defaults to the
     * authorization ID of the connecting user, which is often not the schema holding
     * the migrated objects.
     */
    @Value("${mcp.db.connection.schema:}")
    private String currentSchema;

    @Bean
    @Primary
    public DataSource dataSource() {
        logger.info("Creating main dataSource");
        return createDataSource();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource());
    }

    private DataSource createDataSource() {
        logger.info("createDataSource called");
        HikariConfig config = new HikariConfig();

        if ("secretsmanager".equals(connectionType)) {
            configureFromSecretsManager(config);
        } else {
            configureFromPassword(config);
        }

        config.setConnectionTimeout(20000);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1200000);

        return new HikariDataSource(config);
    }

    /**
     * Parses {@code username:password@hostname:port/database}.
     *
     * <p>Generated passwords routinely contain {@code :} and {@code @}, so the split points
     * are chosen to tolerate them: the username ends at the <em>first</em> colon, and the
     * host section begins after the <em>last</em> {@code @}. Everything between is taken
     * verbatim as the password. A password containing a literal {@code @} is therefore
     * supported, whereas a username containing {@code :} or {@code @} is not.
     */
    private void configureFromPassword(HikariConfig config) {
        if (connectionDetail == null || connectionDetail.isBlank()) {
            throw new IllegalStateException(
                "mcp.db.connection.detail is not set. Expected username:password@hostname:port/database");
        }

        int atIndex = connectionDetail.lastIndexOf('@');
        if (atIndex < 0) {
            throw new IllegalStateException(malformed("missing '@' separating credentials from host"));
        }
        String credentials = connectionDetail.substring(0, atIndex);
        String hostSection = connectionDetail.substring(atIndex + 1);

        // Limit 2: only the first colon delimits username from password, so a password
        // containing colons survives intact.
        String[] userPass = credentials.split(":", 2);
        if (userPass.length < 2) {
            throw new IllegalStateException(malformed("missing ':' separating username from password"));
        }
        String username = userPass[0];
        String password = userPass[1];
        if (username.isEmpty()) {
            throw new IllegalStateException(malformed("username is empty"));
        }
        if (password.isEmpty()) {
            throw new IllegalStateException(malformed("password is empty"));
        }

        // Limit 2: a database name is a single identifier, but splitting defensively keeps
        // any stray '/' out of the port field.
        String[] hostPortDb = hostSection.split("/", 2);
        String database = hostPortDb.length > 1 && !hostPortDb[1].isBlank()
                ? hostPortDb[1]
                : DEFAULT_DATABASE;

        int portIndex = hostPortDb[0].lastIndexOf(':');
        if (portIndex < 0) {
            throw new IllegalStateException(malformed("missing ':' separating hostname from port"));
        }
        String host = hostPortDb[0].substring(0, portIndex);
        String portText = hostPortDb[0].substring(portIndex + 1);
        if (host.isEmpty()) {
            throw new IllegalStateException(malformed("hostname is empty"));
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(malformed("port '" + portText + "' is not a number"), e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException(malformed("port " + port + " is out of range 1-65535"));
        }

        applyConnection(config, host, port, database, username, password);
    }

    /**
     * Builds a parse-failure message. Never includes {@code connectionDetail} itself, as it
     * embeds the password and this message reaches the log.
     */
    private static String malformed(String reason) {
        return "Malformed mcp.db.connection.detail: " + reason
                + ". Expected username:password@hostname:port/database"
                + " (a password containing ':' is supported; consider"
                + " mcp.db.connection.type=secretsmanager to avoid delimiter issues entirely)";
    }

    private void configureFromSecretsManager(HikariConfig config) {
        try {
            String region = connectionDetail.split(":")[3];
            logger.info("Using region: {}", region);
            logger.info("Secret ARN: {}", connectionDetail);

            try (SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(region))
                    .build()) {
                String secretValue = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(connectionDetail).build()
                ).secretString();

                ObjectMapper mapper = new ObjectMapper();
                JsonNode secret = mapper.readTree(secretValue);

                String host = secret.get("host").asText();
                int port = secret.get("port").asInt();
                String database = secret.has("dbname") ? secret.get("dbname").asText() : DEFAULT_DATABASE;
                String username = secret.get("username").asText();
                String password = secret.get("password").asText();

                applyConnection(config, host, port, database, username, password);
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve database credentials: {}", e.getMessage());
            throw new RuntimeException("Failed to retrieve database credentials from Secrets Manager", e);
        }
    }

    private void applyConnection(HikariConfig config, String host, int port, String database,
                                 String username, String password) {
        config.setJdbcUrl("jdbc:db2://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(DRIVER_CLASS);

        if (currentSchema != null && !currentSchema.isBlank()) {
            config.addDataSourceProperty("currentSchema", currentSchema);
            config.setConnectionInitSql("SET CURRENT SCHEMA " + quoteSchema(currentSchema));
        }

        logger.info("Database configuration completed - JDBC URL: jdbc:db2://{}:{}/{} (currentSchema={})",
                host, port, database, currentSchema == null || currentSchema.isBlank() ? "<default>" : currentSchema);
    }

    /**
     * Quotes the configured schema for use in SET CURRENT SCHEMA. The value comes from
     * local configuration rather than end-user input, but it is interpolated into SQL,
     * so reject anything that is not a plain identifier.
     */
    private static String quoteSchema(String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
            throw new IllegalArgumentException(
                "Invalid mcp.db.connection.schema value: must be a plain DB2 identifier");
        }
        return "\"" + schema.toUpperCase() + "\"";
    }
}
