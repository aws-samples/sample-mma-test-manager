package com.mma.testmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DatabaseConnectionService {
    
    private final PostgresCommonService postgresCommonService;
    private final OracleCommonService oracleCommonService;
    private final SQLServerCommonService sqlServerCommonService;
    
    public DatabaseConnectionService(PostgresCommonService postgresCommonService,
                                    OracleCommonService oracleCommonService,
                                    SQLServerCommonService sqlServerCommonService) {
        this.postgresCommonService = postgresCommonService;
        this.oracleCommonService = oracleCommonService;
        this.sqlServerCommonService = sqlServerCommonService;
    }
    
    @Value("${mma.sourcedb.connection.type:secretsmanager}")
    private String sourceDbConnectionType;
    
    @Value("${mma.sourcedb.connection.secret}")
    private String sourceDbSecretArn;
    
    @Value("${mma.targetdb.connection.type:secretsmanager}")
    private String targetDbConnectionType;
    
    @Value("${mma.targetdb.connection.secret}")
    private String targetDbSecretArn;
    
    @Value("${mma.sourcedb.test.type:secretsmanager}")
    private String sourceDbTestType;
    
    @Value("${mma.sourcedb.test.secret}")
    private String sourceDbTestSecretArn;
    
    @Value("${mma.targetdb.test.type:secretsmanager}")
    private String targetDbTestType;
    
    @Value("${mma.targetdb.test.secret}")
    private String targetDbTestSecretArn;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, String>> secretCache = new HashMap<>();
    
    // Admin connection - read-only queries with rollback
    public Map<String, Object> executeOracleQuery(String schema, String sql) {
        Map<String, String> credentials = getCredentials(sourceDbConnectionType, sourceDbSecretArn);
        String jdbcUrl = String.format("jdbc:oracle:thin:@%s:%s/%s", 
            credentials.get("host"), credentials.get("port"), credentials.get("dbname"));
        
        return executeSimpleQuery(jdbcUrl, credentials.get("username"), credentials.get("password"), 
            schema, sql, "oracle");
    }
    
    // Admin connection - read-only queries with rollback
    public Map<String, Object> executeSqlServerQuery(String schema, String sql) {
        Map<String, String> credentials = getCredentials(sourceDbConnectionType, sourceDbSecretArn);
        String jdbcUrl = String.format("jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=true;trustServerCertificate=true", 
            credentials.get("host"), credentials.get("port"), credentials.get("dbname"));
        
        return executeSimpleQuery(jdbcUrl, credentials.get("username"), credentials.get("password"), 
            schema, sql, "sqlserver");
    }
    
    // Admin connection - read-only queries with rollback
    public Map<String, Object> executePostgresQuery(String schema, String sql) {
        Map<String, String> credentials = getCredentials(targetDbConnectionType, targetDbSecretArn);
        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", 
            credentials.get("host"), credentials.get("port"), credentials.get("dbname"));
        
        return executeSimpleQuery(jdbcUrl, credentials.get("username"), credentials.get("password"), 
            schema, sql, "postgres");
    }
    
    // Test user connection - read-only with rollback
    public Map<String, Object> executeOracleTestWithTestUser(String schema, String sql) {
        Map<String, String> credentials = getCredentials(sourceDbTestType, sourceDbTestSecretArn);
        String jdbcUrl = String.format("jdbc:oracle:thin:@%s:%s/%s", 
            credentials.get("host"), credentials.get("port"), credentials.get("dbname"));
        
        return executeTest(jdbcUrl, credentials.get("username"), credentials.get("password"), 
            schema, sql, "oracle");
    }
    
    // Test user connection - read-only with rollback
    public Map<String, Object> executeSqlServerTestWithTestUser(String schema, String sql) {
        Map<String, String> credentials = getCredentials(sourceDbTestType, sourceDbTestSecretArn);
        String jdbcUrl = String.format("jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=true;trustServerCertificate=true", 
            credentials.get("host"), credentials.get("port"), credentials.get("dbname"));
        
        return executeTest(jdbcUrl, credentials.get("username"), credentials.get("password"), 
            schema, sql, "sqlserver");
    }
    
    // Test user connection - read-only with rollback
    public Map<String, Object> executePostgreSQLTestWithTestUser(String schema, String sql) {
        Map<String, String> credentials = getCredentials(targetDbTestType, targetDbTestSecretArn);
        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", 
            credentials.get("host"), credentials.get("port"), credentials.get("dbname"));
        
        return executeTest(jdbcUrl, credentials.get("username"), credentials.get("password"), 
            schema, sql, "postgres");
    }
    
    // Admin connection - DDL execution with commit
    public void executePostgreSQLSql(String schema, String sql) throws Exception {
        Map<String, String> credentials = getCredentials(targetDbConnectionType, targetDbSecretArn);
        String jdbcUrl = buildJdbcUrl(credentials);
        String engine = credentials.get("engine");
        
        loadDriver(engine);  // Safe driver loading with allowlist
        try (Connection conn = DriverManager.getConnection(jdbcUrl, 
                credentials.get("username"), credentials.get("password"))) {
            conn.setAutoCommit(false);
            
            try (Statement stmt = conn.createStatement()) {
                if (schema != null && !schema.isEmpty()) {
                    String validatedSchema = validateSchemaName(schema);
                    stmt.execute("SET search_path TO " + validatedSchema + ", public");
                }
                stmt.execute(sql.trim().replaceAll(";$", ""));
                conn.commit();
                log.info("Successfully executed and committed SQL");
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }
    
    // Simple query execution for DDL retrieval - no multi-statement processing
    private Map<String, Object> executeSimpleQuery(String jdbcUrl, String username, String password,
                                                   String schema, String sql, String engine) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        
        sql = sql.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        
        try {
            loadDriver(engine);  // Safe driver loading with allowlist
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                conn.setAutoCommit(false);
                
                if (schema != null && !schema.isEmpty()) {
                    String validatedSchema = validateSchemaName(schema);
                    try (Statement stmt = conn.createStatement()) {
                        if ("oracle".equalsIgnoreCase(engine)) {
                            stmt.execute("ALTER SESSION SET CURRENT_SCHEMA = " + validatedSchema);
                        } else if ("postgres".equalsIgnoreCase(engine)) {
                            stmt.execute("SET search_path TO " + validatedSchema + ", public");
                        }
                    }
                }
                
                try (Statement stmt = conn.createStatement()) {
                    boolean hasResults = stmt.execute(sql);
                    
                    if (hasResults) {
                        ResultSet rs = stmt.getResultSet();
                        StringBuilder output = new StringBuilder();
                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();
                        
                        while (rs.next()) {
                            for (int i = 1; i <= columnCount; i++) {
                                if (i > 1) output.append(", ");
                                output.append(rs.getString(i));
                            }
                            output.append("\n");
                        }
                        
                        result.put("query_output", output.toString().trim());
                    } else {
                        result.put("query_output", "Query executed successfully");
                    }
                    
                    result.put("status", "SUCCESS");
                    result.put("error_message", null);
                }
                
                conn.rollback();
            }
            
        } catch (Exception e) {
            log.error("Error executing query: {}", e.getMessage());
            result.put("status", "FAILURE");
            result.put("query_output", null);
            result.put("error_message", e.getMessage());
        }
        
        long executionTime = System.currentTimeMillis() - startTime;
        result.put("execution_time_ms", executionTime);
        
        return result;
    }
    
    private Map<String, Object> executeTest(String jdbcUrl, String username, String password, 
                                            String schema, String sql, String engine) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        
        // Split SQL into individual statements based on engine
        List<String> validStatements = splitSqlStatements(sql, engine);
        
        log.info("Executing {} SQL statement(s) for {}", validStatements.size(), engine);
        log.info("Schema: {}", schema);
        
        StringBuilder aggregatedOutput = new StringBuilder();
        boolean hasAnyFailure = false;
        
        try {
            loadDriver(engine);  // Safe driver loading with allowlist
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                conn.setAutoCommit(false);
                
                // Set schema if provided
                if (schema != null && !schema.isEmpty()) {
                    String validatedSchema = validateSchemaName(schema);
                    try (Statement stmt = conn.createStatement()) {
                        if ("oracle".equalsIgnoreCase(engine)) {
                            stmt.execute("ALTER SESSION SET CURRENT_SCHEMA = " + validatedSchema);
                        } else if ("postgres".equalsIgnoreCase(engine)) {
                            stmt.execute("SET search_path TO " + validatedSchema + ", public");
                        }
                    }
                }
                
                // Execute each statement
                for (int i = 0; i < validStatements.size(); i++) {
                    String currentSql = validStatements.get(i);
                    long stmtStartTime = System.currentTimeMillis();
                    
                    aggregatedOutput.append("=== Statement ").append(i + 1).append(" ===\n");
                    aggregatedOutput.append(currentSql).append(";\n");
                    
                    try (Statement stmt = conn.createStatement()) {
                        boolean hasResults = stmt.execute(currentSql);
                        
                        // Capture RAISE NOTICE messages for PostgreSQL
                        StringBuilder notices = new StringBuilder();
                        if ("postgres".equalsIgnoreCase(engine)) {
                            java.sql.SQLWarning warning = stmt.getWarnings();
                            while (warning != null) {
                                notices.append(warning.getMessage()).append("\n");
                                warning = warning.getNextWarning();
                            }
                        }
                        
                        if (hasResults) {
                            ResultSet rs = stmt.getResultSet();
                            StringBuilder output = new StringBuilder();
                            ResultSetMetaData meta = rs.getMetaData();
                            int columnCount = meta.getColumnCount();
                            
                            while (rs.next()) {
                                for (int j = 1; j <= columnCount; j++) {
                                    if (j > 1) output.append(", ");
                                    output.append(rs.getString(j));
                                }
                                output.append("\n");
                            }
                            
                            aggregatedOutput.append("Status: SUCCESS\n");
                            if (notices.length() > 0) {
                                aggregatedOutput.append("Notices: ").append(notices.toString().trim()).append("\n");
                            }
                            aggregatedOutput.append("Result: ").append(output.toString().trim()).append("\n");
                        } else {
                            aggregatedOutput.append("Status: SUCCESS\n");
                            if (notices.length() > 0) {
                                aggregatedOutput.append("Notices: ").append(notices.toString().trim()).append("\n");
                            }
                            aggregatedOutput.append("Result: Query executed successfully\n");
                        }
                        
                        long stmtTime = System.currentTimeMillis() - stmtStartTime;
                        aggregatedOutput.append("Time: ").append(stmtTime).append("ms\n");
                        
                    } catch (Exception e) {
                        hasAnyFailure = true;
                        long stmtTime = System.currentTimeMillis() - stmtStartTime;
                        
                        aggregatedOutput.append("Status: FAILURE\n");
                        aggregatedOutput.append("Error: ").append(e.getMessage()).append("\n");
                        aggregatedOutput.append("Time: ").append(stmtTime).append("ms\n");
                    }
                    
                    aggregatedOutput.append("\n");
                }
                
                conn.rollback(); // Always rollback for read-only testing
            }
            
            if (hasAnyFailure) {
                result.put("status", "FAILURE");
                result.put("query_output", null);
                result.put("error_message", aggregatedOutput.toString().trim());
            } else {
                result.put("status", "SUCCESS");
                result.put("query_output", aggregatedOutput.toString().trim());
                result.put("error_message", null);
            }
            
        } catch (Exception e) {
            log.error("Error executing test: {}", e.getMessage());
            result.put("status", "FAILURE");
            result.put("query_output", null);
            result.put("error_message", e.getMessage());
        }
        
        long executionTime = System.currentTimeMillis() - startTime;
        result.put("execution_time_ms", executionTime);
        
        return result;
    }
    
    /**
     * Validate and sanitize schema name to prevent SQL injection.
     * Schema names must be valid SQL identifiers (alphanumeric and underscore only).
     */
    private String validateSchemaName(String schema) {
        if (schema == null || schema.isEmpty()) {
            return schema;
        }
        // Allow only alphanumeric characters, underscores, and hyphens
        if (!schema.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schema + 
                ". Schema names must contain only alphanumeric characters, underscores, and hyphens.");
        }
        return schema;
    }
    
    private List<String> splitSqlStatements(String sql, String engine) {
        switch (engine.toLowerCase()) {
            case "postgres":
            case "mysql":
                return postgresCommonService.splitSqlStatements(sql);
            case "oracle":
                return oracleCommonService.splitSqlStatements(sql);
            case "sqlserver":
                return sqlServerCommonService.splitSqlStatements(sql);
            default:
                return Arrays.stream(sql.split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }
    
    
    private Map<String, String> getCredentials(String type, String detail) {
        if ("password".equals(type)) {
            return parsePasswordCredentials(detail);
        } else {
            return getSecretCredentials(detail);
        }
    }
    
    private Map<String, String> parsePasswordCredentials(String detail) {
        // Format: engine://username:password@hostname:port/database
        String[] engineAndRest = detail.split("://");
        String engine = engineAndRest[0];
        
        String[] parts = engineAndRest[1].split("@");
        String[] userPass = parts[0].split(":");
        String[] hostPortDb = parts[1].split("/");
        String[] hostPort = hostPortDb[0].split(":");
        
        Map<String, String> credentials = new HashMap<>();
        credentials.put("engine", engine);
        credentials.put("username", userPass[0]);
        credentials.put("password", userPass[1]);
        credentials.put("host", hostPort[0]);
        credentials.put("port", hostPort[1]);
        credentials.put("dbname", hostPortDb[1]);
        
        return credentials;
    }
    
    public Map<String, String> getSecretCredentials(String secretArn) {
        if (secretCache.containsKey(secretArn)) {
            return secretCache.get(secretArn);
        }
        
        try (SecretsManagerClient client = SecretsManagerClient.builder().build()) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build();
            
            String secretString = client.getSecretValue(request).secretString();
            JsonNode json = objectMapper.readTree(secretString);
            
            Map<String, String> credentials = new HashMap<>();
            credentials.put("engine", json.path("engine").asText());
            credentials.put("host", json.path("host").asText());
            credentials.put("port", json.path("port").asText());
            credentials.put("dbname", json.path("dbname").asText());
            credentials.put("username", json.path("username").asText());
            credentials.put("password", json.path("password").asText());
            
            secretCache.put(secretArn, credentials);
            return credentials;
        } catch (Exception e) {
            log.error("Error retrieving secret", e);
            throw new RuntimeException("Failed to retrieve database credentials", e);
        }
    }
    
    private String buildJdbcUrl(Map<String, String> credentials) {
        String engine = credentials.get("engine");
        String host = credentials.get("host");
        String port = credentials.get("port");
        String dbname = credentials.get("dbname");
        
        if ("oracle".equalsIgnoreCase(engine)) {
            return String.format("jdbc:oracle:thin:@%s:%s/%s", host, port, dbname);
        } else if ("postgres".equalsIgnoreCase(engine)) {
            return String.format("jdbc:postgresql://%s:%s/%s", host, port, dbname);
        } else if ("sqlserver".equalsIgnoreCase(engine)) {
            return String.format("jdbc:sqlserver://%s:%s;databaseName=%s", host, port, dbname);
        } else if ("mysql".equalsIgnoreCase(engine)) {
            return String.format("jdbc:mysql://%s:%s/%s", host, port, dbname);
        } else {
            throw new IllegalArgumentException("Unsupported database engine: " + engine);
        }
    }
    
    private String getDriverClass(String engine) {
        if ("oracle".equalsIgnoreCase(engine)) {
            return "oracle.jdbc.OracleDriver";
        } else if ("postgres".equalsIgnoreCase(engine)) {
            return "org.postgresql.Driver";
        } else if ("sqlserver".equalsIgnoreCase(engine)) {
            return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        } else if ("mysql".equalsIgnoreCase(engine)) {
            return "com.mysql.cj.jdbc.Driver";
        } else {
            throw new IllegalArgumentException("Unsupported database engine: " + engine);
        }
    }
    
    /**
     * Safely load JDBC driver using allowlist approach.
     * This method prevents unsafe reflection by only loading known, trusted driver classes.
     */
    private void loadDriver(String engine) throws ClassNotFoundException {
        // allowlist approach - only load known, trusted drivers
        switch (engine.toLowerCase()) {
            case "oracle":
                Class.forName("oracle.jdbc.OracleDriver");
                break;
            case "postgres":
                Class.forName("org.postgresql.Driver");
                break;
            case "sqlserver":
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                break;
            case "mysql":
                Class.forName("com.mysql.cj.jdbc.Driver");
                break;
            default:
                throw new IllegalArgumentException("Unsupported database engine: " + engine);
        }
    }
}
