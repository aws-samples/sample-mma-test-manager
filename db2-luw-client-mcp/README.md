# Db2 LUW MCP Server

Model Context Protocol (MCP) server for IBM Db2 for Linux, UNIX and Windows (LUW) database operations.

> **⚠️ Security Notice**: This MCP server is designed for AI-assisted database operations and intentionally allows flexible SQL execution without built-in guardrails. Users are responsible for applying appropriate access controls at the database level. It is strongly recommended to configure the database connection with a **read-only user** with access restricted to only the required schemas and tables. Never connect using an admin or privileged account in production environments.

## Available Tools

- `db2_execute_sql` - Execute SQL statements
- `db2_execute_testcase_readonly` - Execute test cases with read-only guarantee
- `db2_execute_testcase_rollback` - Execute test cases with automatic rollback

## Configuration

Edit `db2-luw-client-mcp/application-secretsmanager.properties`:

```properties
# Database connection (password mode)
mcp.db.connection.type=password
mcp.db.connection.detail=username:password@hostname:port/database

# Database connection (secrets manager mode)
mcp.db.connection.type=secretsmanager
mcp.db.connection.detail=arn:aws:secretsmanager:region:account:secret:name

# Optional: default schema (DB2 CURRENT SCHEMA)
mcp.db.connection.schema=DB2INST1
```

The default Db2 LUW port is `50000` (`50001` for SSL-enabled instances). The database name is the LUW database (e.g. `SAMPLE`), not a service name.

Unlike Oracle, Db2 resolves unqualified object names against the connecting user's authorization ID. If your migrated objects live in a different schema, set `mcp.db.connection.schema` so unqualified queries resolve correctly.

## Build and Run

```bash
./mvnw clean package
java -jar target/db2-luw-mcp-server-1.0.0.jar --spring.config.location=./application-secretsmanager.properties
```

## Deployment

- **Workshop / one-click (Amazon Linux)** → [../one-click-deployment/db2-to-postgres/README.md](../one-click-deployment/db2-to-postgres/README.md). This is how the MMA workshop tracks deploy MCP servers: an AL2023 instance with VS Code server, Java 21 and Maven from `dnf`, `build-all.sh` to compile, and SSM documents to write the agent config. ⚠️ Work in progress — see its checklist.
- **Manual (any Linux host with Kiro CLI)** → build the JAR as above, then register it in `~/.kiro/agents/mma-agent.json` using the snippet in [Kiro CLI Integration](#kiro-cli-integration) below. Add the server to the existing `mcpServers` object rather than replacing the file, so servers such as `postgres-client-mcp` are preserved, and list `@db2-luw-client-mcp` in both `tools` and `allowedTools` — a tool absent from `allowedTools` prompts for approval on every call.

Like the other MCP servers in this repository, deployment targets Amazon Linux. The server itself is a portable stdio fat JAR with no OS-specific code, but only the Linux path is documented and tested.

## Testing

See [TESTING.md](TESTING.md) for a layered test procedure, including MCP protocol checks that run without a database.

## Kiro CLI Integration

Configure in `~/.kiro/agents/mma-agent.json`. Merge into the existing `mcpServers`
object — do not replace the file, or servers already registered will be lost:

```json
{
  "$schema": "https://raw.githubusercontent.com/aws/amazon-q-developer-cli/refs/heads/main/schemas/agent-v1.json",
  "name": "mma-agent",
  "description": "MMA test manager agent with Db2 LUW MCP server",
  "mcpServers": {
    "db2-luw-client-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/home/ec2-user/sample-mma-test-manager-main/db2-luw-client-mcp/target/db2-luw-mcp-server-1.0.0.jar",
        "--spring.config.location=/home/ec2-user/sample-mma-test-manager-main/db2-luw-client-mcp/application-secretsmanager.properties"
      ],
      "timeout": 300000,
      "disabled": false
    }
  },
  "tools": ["fs_read", "fs_write", "execute_bash", "use_aws", "@db2-luw-client-mcp"],
  "allowedTools": ["fs_read", "fs_write", "execute_bash", "use_aws", "@db2-luw-client-mcp"],
  "useLegacyMcpJson": true
}
```

`--spring.config.location` must be an absolute path; a relative path resolves against
Kiro CLI's working directory rather than the JAR's location. Validate the file before
starting Kiro, since malformed JSON fails silently:

```bash
python3 -m json.tool ~/.kiro/agents/mma-agent.json > /dev/null && echo "JSON OK"
```

Then confirm registration — `/mcp` shows whether the server loaded, `/tools` lists the
three `db2_*` tools:

```bash
kiro-cli chat --agent mma-agent
```

## JDBC Driver

This server uses the IBM Data Server Driver for JDBC and SQLJ (JCC), which is published on Maven Central and resolved automatically by the build — no manual driver installation is required.

| Artifact | Driver class | JDBC Spec | Java Requirement | Db2 LUW Versions Supported |
|----------|--------------|-----------|------------------|----------------------------|
| `com.ibm.db2:jcc:11.5.9.0` | `com.ibm.db2.jcc.DB2Driver` | JDBC 4.2 | Java 8+ | Db2 10.5, 11.1, 11.5, 12.1 |

The JCC driver is backward and forward compatible across supported Db2 LUW server levels. To target a specific server level, override the version in `pom.xml` (for example `12.1.5.0` for Db2 12.1 features).

For SSL-enabled instances, append the connection properties to the JDBC URL by adjusting `DatabaseConfig`, or add them as Hikari data source properties:

```
sslConnection=true;sslTrustStoreLocation=/path/to/truststore;
```

## Security Recommendation

For production use, create a dedicated read-only database user. Db2 LUW authenticates against operating system users, so create the OS user first, then grant only the privileges required:

```sql
-- Connect as an instance owner / SECADM
GRANT CONNECT ON DATABASE TO USER MCP_READONLY;

-- Grant SELECT on the specific schemas/tables under test
GRANT SELECT ON TABLE DB2INST1.YOUR_TABLE TO USER MCP_READONLY;

-- Optional: catalog access for schema discovery
GRANT SELECT ON TABLE SYSCAT.TABLES TO USER MCP_READONLY;
GRANT SELECT ON TABLE SYSCAT.COLUMNS TO USER MCP_READONLY;
```

Avoid `DATAACCESS`, `DBADM`, and `SECADM` authorities for this connection.
