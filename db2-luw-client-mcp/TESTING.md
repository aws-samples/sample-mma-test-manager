# Testing db2-luw-client-mcp

Four levels, cheapest first. Levels 1–2 need no database at all; level 3 is the real end-to-end check.

---

## Level 1 — Build

```bash
./mvnw clean package -DskipTests          # Windows: .\mvnw.cmd clean package -DskipTests
```

Confirm the driver is bundled (no separate driver install should be needed):

```bash
unzip -l target/db2-luw-mcp-server-1.0.0.jar | grep jcc
# BOOT-INF/lib/jcc-11.5.9.0.jar
```

---

## Level 2 — MCP protocol, without a database

The server registers its tools before it needs a working connection, so you can verify the MCP wiring offline. This catches the most common silent failure: annotation scanning not picking up the tools.

Send an `initialize` / `tools/list` sequence on stdin. The `sleep` calls matter — the server shuts down as soon as stdin closes, so a plain `printf | java` pipeline exits before it can answer.

**macOS / Linux:**

```bash
{ printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  sleep 3
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  sleep 5
} | java -jar target/db2-luw-mcp-server-1.0.0.jar \
      --spring.config.location=./application-secretsmanager.properties \
      --mcp.db.connection.type=password \
      --mcp.db.connection.detail='u:p@127.0.0.1:50000/SAMPLE' \
      --logging.file.name=/tmp/t.log 2>/dev/null
```

Expected: an `initialize` result, then a `tools/list` result naming `db2_execute_sql`, `db2_execute_testcase_readonly`, and `db2_execute_testcase_rollback`.

The authoritative check is in the log, independent of protocol timing:

```bash
grep "Registered tools" /tmp/t.log
# Registered tools: 3
```

If that says `0`, the tools did not register — check `spring.ai.mcp.server.annotation-scanner.base-packages=com.example` and that `Db2LuwMcpTools` is on the classpath. A `tools/list` that returns nothing while the log says `3` is just the shutdown race, not a real failure.

Note this offline test only reaches the tool-registration stage. With the shipped config, Hikari fails fast on an unreachable host, so the process then exits — that is expected here and is not a tool-registration problem.

---

## Level 3 — Against a real Db2

This is the only level that exercises SQL execution, result mapping, and the read-only / rollback guarantees.

### 3a. Reachability first

Rule out the network before debugging configuration:

```bash
nc -vz <db2-host> 50000
```

### 3b. Start the server

```bash
java -jar target/db2-luw-mcp-server-1.0.0.jar \
  --spring.config.location=./application-secretsmanager.properties
```

A healthy start prints **nothing** and hangs waiting on stdin. Console silence is by design — stdout is the MCP channel. Confirm in the log:

```
Database configuration completed - JDBC URL: jdbc:db2://<host>:50000/<db> (currentSchema=...)
HikariPool-1 - Start completed.
Registered tools: 3
```

`Registered tools: 3` confirms MCP wiring. If the process exits immediately instead of hanging, the database connection failed:

| Symptom | Cause and fix |
|---|---|
| `ERRORCODE=-4499, SQLSTATE=08001` | TCP connect failed. Check the host, port 50000, and security groups. |
| `ERRORCODE=-4214, SQLSTATE=28000` | Authentication failed. Db2 LUW authenticates against OS users on the Db2 host. |
| `SQLCODE=-1013` | Database name not found. Use the LUW database name (e.g. `SAMPLE`), not a service name. |
| "table not found" on unqualified names | Set `mcp.db.connection.schema` to the schema owning the objects. |
| Log file never appears | `logging.file.name` path not writable, or its parent directory does not exist. |

### 3c. Through Kiro CLI

Register the server (see the Kiro CLI Integration section in [README.md](README.md)), then:

```bash
kiro-cli chat --agent mma-agent
```

```
/tools
```

Work up from a query that needs no user tables:

| # | Prompt | Verifies |
|---|--------|----------|
| 1 | `Use db2_execute_sql: SELECT CURRENT SCHEMA, CURRENT SERVER FROM SYSIBM.SYSDUMMY1` | Connectivity, effective schema |
| 2 | `Use db2_execute_sql: SELECT TABSCHEMA, TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA NOT LIKE 'SYS%' FETCH FIRST 10 ROWS ONLY` | Catalog access, multi-row mapping |
| 3 | `Use db2_execute_sql: SELECT * FROM <your_table> FETCH FIRST 5 ROWS ONLY` | Grants on real tables, type mapping |
| 4 | `Use db2_execute_testcase_readonly: SELECT COUNT(*) FROM <your_table>` | Read-only path plus `executionTimeMs` |
| 5 | `Use db2_execute_testcase_rollback: SELECT COUNT(*) FROM <your_table>` | Rollback path |

Test 1 doubles as a schema check: if `CURRENT SCHEMA` is not the schema holding your objects, unqualified queries will fail with "table not found" — set `mcp.db.connection.schema`.

### 3d. Verify rollback actually rolls back

The rollback tool's whole purpose is leaving no trace, so test it with a statement that *would* persist. On a disposable table:

```
Use db2_execute_sql: SELECT COUNT(*) FROM <scratch_table>
Use db2_execute_testcase_rollback: INSERT INTO <scratch_table> VALUES (...)
Use db2_execute_sql: SELECT COUNT(*) FROM <scratch_table>
```

The count must be unchanged. If it increased, rollback is not working — stop and investigate before using this against anything you care about.

A read-only user cannot run step 2, so this test needs a writable account on a throwaway table. Do not run it against production data.

---

## Level 4 — Local Db2 in a container (optional)

For a self-contained functional test without a real server. Requires Docker and ~2 GB RAM; the image is IBM-licensed and accepting the licence is your responsibility.

```bash
docker run -d --name db2test --privileged \
  -p 50000:50000 \
  -e LICENSE=accept -e DB2INST1_PASSWORD=<password> -e DBNAME=testdb \
  icr.io/db2_community/db2

# First-time init takes several minutes; wait for "Setup has completed"
docker logs -f db2test
```

Then point the server at it:

```bash
java -jar target/db2-luw-mcp-server-1.0.0.jar \
  --spring.config.location=./application-secretsmanager.properties \
  --mcp.db.connection.type=password \
  --mcp.db.connection.detail='db2inst1:<password>@127.0.0.1:50000/testdb'
```

This is also the safest place to run the level 3d rollback test, since the data is disposable.

```bash
docker rm -f db2test    # tear down
```

---

## Current verification status

Confirmed on macOS against this build:

- Build succeeds; JCC driver bundled in the fat JAR
- `Registered tools: 3`; `tools/list` returns all three with `{"sql": string}` schemas, `required: ["sql"]`
- `tools/call` round-trips: a DB failure is caught and returned as `{"success":false,"error":"..."}` rather than crashing the server
- stdout stays byte-for-byte empty apart from MCP JSON — safe for stdio transport
- `logging.file.name` override is honoured

Not yet verified (needs a live Db2):

- SQL execution and result-set mapping against real tables
- Read-only and rollback isolation guarantees
- Secrets Manager credential retrieval
- `SET CURRENT SCHEMA` behaviour
