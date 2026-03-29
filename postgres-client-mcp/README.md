# PostgreSQL MCP Server

Model Context Protocol (MCP) server for PostgreSQL database operations using Spring AI MCP framework.

> **⚠️ Security Notice**: This MCP server is designed for AI-assisted database operations and intentionally allows flexible SQL execution without built-in guardrails. Users are responsible for applying appropriate access controls at the database level. It is strongly recommended to configure the database connection with a **read-only user** with access restricted to only the required schemas and tables. Never connect using an admin or privileged account in production environments.

## Available Tools

- `postgresql_execute_sql` - Execute SQL statements and return results
- `postgresql_execute_testcase_readonly` - Execute test cases with read-only guarantee
- `postgresql_execute_testcase_rollback` - Execute test cases with automatic rollback

## Configuration

Edit `postgres-client-mcp/application-secretsmanager.properties`:

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
java -jar target/postgresql-mcp-server-1.0.0.jar --spring.config.location=./application-secretsmanager.properties
```

## Kiro CLI Integration

Configure in `~/.kiro/agents/mma-agent.json`:

```json
{
  "mcpServers": {
    "postgres-client-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/postgres-client-mcp/target/postgresql-mcp-server-1.0.0.jar",
        "--spring.config.location=/absolute/path/to/postgres-client-mcp/application-secretsmanager.properties"
      ],
      "timeout": 300000
    }
  }
}
```

## Technical Details

- Spring Boot 4.0.3 with Java 21
- Spring AI MCP 1.0.2
- stdio protocol for stable communication
- AWS Secrets Manager support
