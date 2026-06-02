# Sybase ASE MCP Server

Model Context Protocol (MCP) server for Sybase ASE database operations.

> **⚠️ Security Notice**: This MCP server is designed for AI-assisted database operations and intentionally allows flexible SQL execution without built-in guardrails. Users are responsible for applying appropriate access controls at the database level. It is strongly recommended to configure the database connection with a **read-only user** with access restricted to only the required schemas and tables. Never connect using an admin or privileged account in production environments.

## Available Tools

- `sybase_execute_sql` - Execute SQL statements
- `sybase_execute_testcase_readonly` - Execute test cases with read-only guarantee
- `sybase_execute_testcase_rollback` - Execute test cases with automatic rollback

## Configuration

Edit `sybase-client-mcp/application-secretsmanager.properties`:

```properties
# Database connection (password mode)
mcp.db.connection.type=password
mcp.db.connection.detail=username:password@hostname:port/database

# Database connection (secrets manager mode)
mcp.db.connection.type=secretsmanager
mcp.db.connection.detail=arn:aws:secretsmanager:region:account:secret:name
```

## Build and Run

```bash
./mvnw clean package
java -jar target/sybase-mcp-server-1.0.0.jar --spring.config.location=./application-secretsmanager.properties
```

## Kiro CLI Integration

Configure in `~/.kiro/agents/mma-agent.json`:

```json
{
  "mcpServers": {
    "sybase-client-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/sybase-client-mcp/target/sybase-mcp-server-1.0.0.jar",
        "--spring.config.location=/absolute/path/to/sybase-client-mcp/application-secretsmanager.properties"
      ],
      "timeout": 300000
    }
  }
}
```

## jConnect Driver Compatibility

| Driver | JDBC Spec | Java Requirement | SAP ASE Versions Supported |
|--------|-----------|-----------------|---------------------------|
| **jconn4.jar** | JDBC 4.0 | Java 6+ | ASE 12.5, 15.0, 15.5, 15.7, 16.0 |
| **jconn42.jar** | JDBC 4.2 | Java 8+ | ASE 15.7, 16.0 (SP03 PL09+) |

Both drivers are backward compatible — they can connect to older ASE servers. The `jconn42.jar` (JDBC 4.2) is recommended for Java 8+ and is used by this MCP server.

The driver jar is not included in this repository (licensed SAP software). Install it to your local Maven repository:

```bash
mvn install:install-file \
  -Dfile=/path/to/jconn42.jar \
  -DgroupId=com.sybase \
  -DartifactId=jconn42 \
  -Dversion=16.0.SP04 \
  -Dpackaging=jar
```

The jar is located on the SAP ASE server at: `/opt/sap/jConnect-16_0/classes/jconn42.jar`

## Security Recommendation

For production use, create a dedicated read-only database user:

```sql
sp_addlogin mcp_readonly, 'your_secure_password', master
USE your_database
GO
sp_adduser mcp_readonly
GO
GRANT SELECT ON your_database TO mcp_readonly
GO
```
