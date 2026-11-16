import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Db2 SQL script runner over JDBC.
 *
 * Exists so that demo schema deployment needs no IBM client install: the IBM JCC
 * driver is already on the box (bundled in db2-luw-mcp-server-1.0.0.jar, or as a
 * standalone jcc jar), and this runner is the only missing piece.
 *
 * Single-file source program - run directly on Java 11+, no compile step:
 *
 *   java -cp jcc-11.5.9.0.jar Db2ScriptRunner.java \
 *        "jdbc:db2://host:50000/SAMPLE" db2inst1 "$PASS" 01_schema.sql ...
 *
 * Statement terminator handling: Db2 routine bodies (CREATE PROCEDURE, TRIGGER,
 * FUNCTION with compound BEGIN...END) contain inner semicolons, so a naive split
 * on ';' corrupts them. This runner supports the conventional Db2 CLP
 * alternative-terminator directive, so scripts can do:
 *
 *   --#SET TERMINATOR @
 *   CREATE PROCEDURE p() BEGIN ... ; ... ; END @
 *   --#SET TERMINATOR ;
 *
 * Exit code is non-zero if any statement fails, so an SSM step fails loudly
 * rather than reporting a false success.
 */
public final class Db2ScriptRunner {

    private static final String DRIVER = "com.ibm.db2.jcc.DB2Driver";

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: Db2ScriptRunner <jdbcUrl> <user> <password> <script.sql> [more.sql ...]");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];

        // Fail fast with a clear message if the driver is not on the classpath.
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: " + DRIVER + " not on classpath. Pass -cp <jcc jar>.");
            System.exit(3);
        }

        int failures = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            // Explicit commit per script so a later script failing does not roll
            // back schema objects an earlier one created.
            conn.setAutoCommit(false);
            System.out.printf("Connected: %s as %s%n", url, user);

            for (int i = 3; i < args.length; i++) {
                failures += runScript(conn, Path.of(args[i]));
            }
        }

        if (failures > 0) {
            System.err.printf("FAILED: %d statement(s) errored%n", failures);
            System.exit(1);
        }
        System.out.println("All scripts completed successfully.");
    }

    private static int runScript(Connection conn, Path file) throws IOException, SQLException {
        System.out.printf("%n--- %s ---%n", file);
        List<String> statements = split(Files.readString(file));
        int failures = 0;
        int n = 0;

        for (String sql : statements) {
            n++;
            try (Statement st = conn.createStatement()) {
                boolean hasRs = st.execute(sql);
                if (hasRs) {
                    try (ResultSet rs = st.getResultSet()) {
                        printResultSet(rs);
                    }
                } else {
                    int c = st.getUpdateCount();
                    System.out.printf("  [%d] ok%s%n", n, c >= 0 ? " (" + c + " rows)" : "");
                }
                conn.commit();
            } catch (SQLException e) {
                failures++;
                conn.rollback();
                // Keep going: demo scripts are often idempotent-ish and one
                // "already exists" should not abort the whole deployment.
                System.err.printf("  [%d] ERROR SQLSTATE=%s code=%d: %s%n    SQL: %s%n",
                        n, e.getSQLState(), e.getErrorCode(), e.getMessage(), preview(sql));
            }
        }
        System.out.printf("--- %s: %d statement(s), %d error(s) ---%n", file, n, failures);
        return failures;
    }

    private static void printResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        StringBuilder header = new StringBuilder("      ");
        for (int i = 1; i <= cols; i++) {
            header.append(md.getColumnLabel(i)).append(i < cols ? " | " : "");
        }
        System.out.println(header);
        int rows = 0;
        while (rs.next() && rows < 20) {
            StringBuilder line = new StringBuilder("      ");
            for (int i = 1; i <= cols; i++) {
                line.append(rs.getObject(i)).append(i < cols ? " | " : "");
            }
            System.out.println(line);
            rows++;
        }
    }

    /**
     * Splits a script into statements, honouring --#SET TERMINATOR directives and
     * skipping comments and string literals so that terminators inside them are
     * not treated as statement boundaries.
     */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char terminator = ';';
        int i = 0;
        int len = script.length();

        while (i < len) {
            char c = script.charAt(i);

            // --#SET TERMINATOR x  (and plain -- line comments)
            if (c == '-' && i + 1 < len && script.charAt(i + 1) == '-') {
                int eol = script.indexOf('\n', i);
                if (eol < 0) eol = len;
                String comment = script.substring(i, eol);
                String upper = comment.toUpperCase();
                int idx = upper.indexOf("SET TERMINATOR");
                if (idx >= 0) {
                    String rest = comment.substring(idx + "SET TERMINATOR".length()).trim();
                    if (!rest.isEmpty()) terminator = rest.charAt(0);
                }
                i = eol;
                continue;
            }

            // /* block comment */
            if (c == '/' && i + 1 < len && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = (end < 0) ? len : end + 2;
                continue;
            }

            // 'string literal' - copy verbatim, '' is an escaped quote
            if (c == '\'') {
                cur.append(c);
                i++;
                while (i < len) {
                    char d = script.charAt(i);
                    cur.append(d);
                    i++;
                    if (d == '\'') {
                        if (i < len && script.charAt(i) == '\'') { cur.append('\''); i++; }
                        else break;
                    }
                }
                continue;
            }

            // "quoted identifier"
            if (c == '"') {
                cur.append(c);
                i++;
                while (i < len) {
                    char d = script.charAt(i);
                    cur.append(d);
                    i++;
                    if (d == '"') break;
                }
                continue;
            }

            if (c == terminator) {
                addIfNotBlank(out, cur);
                cur.setLength(0);
                i++;
                continue;
            }

            cur.append(c);
            i++;
        }
        addIfNotBlank(out, cur);
        return out;
    }

    private static void addIfNotBlank(List<String> out, StringBuilder sb) {
        String s = sb.toString().trim();
        if (!s.isEmpty()) out.add(s);
    }

    private static String preview(String sql) {
        String one = sql.replaceAll("\\s+", " ").trim();
        return one.length() > 120 ? one.substring(0, 120) + "..." : one;
    }
}
