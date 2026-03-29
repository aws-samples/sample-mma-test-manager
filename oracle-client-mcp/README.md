# Oracle MCP Server

Model Context Protocol (MCP) server for Oracle database operations.

> **⚠️ Security Notice**: This MCP server is designed for AI-assisted database operations and intentionally allows flexible SQL execution without built-in guardrails. Users are responsible for applying appropriate access controls at the database level. It is strongly recommended to configure the database connection with a **read-only user** with access restricted to only the required schemas and tables. Never connect using an admin or privileged account in production environments.

## Available Tools

- `oracle_execute_sql` - Execute SQL statements
- `oracle_execute_testcase_readonly` - Execute test cases with read-only guarantee
- `oracle_execute_testcase_rollback` - Execute test cases with automatic rollback

## Configuration

Edit `oracle-client-mcp/application-secretsmanager.properties`:

```properties
# Database connection (password mode)
mcp.db.connection.type=password
mcp.db.connection.detail=username:password@hostname:port/service

# Database connection (secrets manager mode)
mcp.db.connection.type=secretsmanager
mcp.db.connection.detail=arn:aws:secretsmanager:region:account:secret:name
```

## Build and Run

```bash
./mvnw clean package
java -jar target/oracle-mcp-server-1.0.0.jar --spring.config.location=./application-secretsmanager.properties
```

## Kiro CLI Integration

Configure in `~/.kiro/agents/mma-agent.json`:

```json
{
  "mcpServers": {
    "oracle-client-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/oracle-client-mcp/target/oracle-mcp-server-1.0.0.jar",
        "--spring.config.location=/absolute/path/to/oracle-client-mcp/application-secretsmanager.properties"
      ],
      "timeout": 300000
    }
  }
}
```

## Security Recommendation

For production use, create a dedicated read-only database user:

```sql
CREATE USER mcp_readonly IDENTIFIED BY "your_secure_password";
GRANT CONNECT TO mcp_readonly;
GRANT SELECT ANY TABLE TO mcp_readonly;
GRANT SELECT ANY DICTIONARY TO mcp_readonly;
```
