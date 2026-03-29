# SQL Server MCP Server

Model Context Protocol (MCP) server for Microsoft SQL Server database operations.

> **⚠️ Security Notice**: This MCP server is designed for AI-assisted database operations and intentionally allows flexible SQL execution without built-in guardrails. Users are responsible for applying appropriate access controls at the database level. It is strongly recommended to configure the database connection with a **read-only user** with access restricted to only the required schemas and tables. Never connect using an admin or privileged account in production environments.

## Available Tools

- `mssql_execute_sql` - Execute SQL statements
- `mssql_execute_testcase_readonly` - Execute test cases with read-only guarantee
- `mssql_execute_testcase_rollback` - Execute test cases with automatic rollback

## Configuration

Edit `sqlserver-client-mcp/application-secretsmanager.properties`:

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
java -jar target/sqlserver-mcp-server-1.0.0.jar --spring.config.location=./application-secretsmanager.properties
```

## Kiro CLI Integration

Configure in `~/.kiro/agents/mma-agent.json`:

```json
{
  "mcpServers": {
    "sqlserver-client-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/sqlserver-client-mcp/target/sqlserver-mcp-server-1.0.0.jar",
        "--spring.config.location=/absolute/path/to/sqlserver-client-mcp/application-secretsmanager.properties"
      ],
      "timeout": 300000
    }
  }
}
```

## Security Recommendation

For production use, create a dedicated read-only database user:

```sql
CREATE LOGIN mcp_readonly WITH PASSWORD = 'your_secure_password';
USE your_database;
CREATE USER mcp_readonly FOR LOGIN mcp_readonly;
ALTER ROLE db_datareader ADD MEMBER mcp_readonly;
```
