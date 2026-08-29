# Db2 tools

## Db2ScriptRunner.java

Runs `.sql` scripts against Db2 over JDBC. Exists so that schema deployment needs **no IBM client install** — the IBM JCC driver is already present (bundled inside `target/db2-luw-mcp-server-1.0.0.jar` after a build), and this runner is the only missing piece.

Why this rather than IBM's own CLI: the `db2` command and CLPPlus ship in the IBM Data Server Driver Package, which requires an IBM account and cannot be fetched unattended during provisioning. The JCC driver, by contrast, comes from Maven Central. Unlike Microsoft's `mssql-tools18` — which the SQL Server track installs with a plain `dnf install` — there is no public IBM repository to pull a Db2 CLI from.

### Usage

Single-file source program: Java 11+ runs it directly, no compile step.

```bash
# Extract the driver from the MCP server jar (one-off)
unzip -o -j target/db2-luw-mcp-server-1.0.0.jar 'BOOT-INF/lib/jcc-*.jar' -d /tmp/db2driver

# Run one or more scripts
java -cp /tmp/db2driver/jcc-11.5.9.0.jar tools/Db2ScriptRunner.java \
  "jdbc:db2://$DB2_HOST:50000/SAMPLE" db2inst1 "$DB2_PASS" \
  01_schema.sql 02_data.sql
```

Exit codes: `0` all statements succeeded, `1` at least one failed, `2` bad usage, `3` driver not on classpath. A non-zero exit makes an SSM provisioning step fail loudly rather than reporting false success.

### Statement terminators

Db2 routine bodies contain inner semicolons, so splitting naively on `;` corrupts `CREATE PROCEDURE`, `CREATE TRIGGER`, and compound `BEGIN ... END` blocks. The runner supports the conventional Db2 CLP directive:

```sql
--#SET TERMINATOR @
CREATE PROCEDURE demo.p1()
BEGIN
    DECLARE x INT;
    SET x = 1;
END @
--#SET TERMINATOR ;
```

The splitter also ignores terminators inside `'string literals'`, `"quoted identifiers"`, `-- line comments`, and `/* block comments */`.

Verified against a script combining all five cases; it produced the expected 4 statements with the procedure body intact.

### Error handling

Each statement commits individually. A failure is logged with SQLSTATE and error code, rolled back, and execution continues — so one "already exists" does not abort the whole deployment — but the process still exits non-zero at the end.

### Status

The runner is tested for statement splitting, driver loading, and connection attempt. It has **not** been run against a live Db2 instance, and no Db2-dialect demo scripts exist yet (see the schema-deployment placeholder in `one-click-deployment/db2-to-postgres/mma-nested-stacks/demo-infrastructure.yaml`).
