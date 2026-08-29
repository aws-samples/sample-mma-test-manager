package com.mma.testmanager.service;

import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.Dependency;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Db2 LUW-specific operations for DMS Schema Conversion.
 *
 * <p>Db2 exposes its catalog through the SYSCAT views rather than Oracle's DBA_*
 * views or SQL Server's sys.* / INFORMATION_SCHEMA. Unlike SQL Server there is no
 * OBJECT_DEFINITION function and unlike Sybase there is no sp_helptext, so the
 * original source text for routines, views and triggers is read from the TEXT
 * column that Db2 stores alongside each object.
 *
 * <p>Schema and object names are folded to upper case before being compared against
 * the catalog, because Db2 stores unquoted identifiers in upper case.
 */
@Service
@Slf4j
public class Db2CommonService {
    private final DatabaseConnectionService dbConnectionService;
    private final DatabaseObjectRepository objectRepository;

    public Db2CommonService(@Lazy DatabaseConnectionService dbConnectionService,
                            DatabaseObjectRepository objectRepository) {
        this.dbConnectionService = dbConnectionService;
        this.objectRepository = objectRepository;
    }

    public String extractSourceDdl(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        String schema = obj.getSourceSchemaName();
        String objectName = obj.getSourceObjectName();
        String objectType = obj.getSourceObjectType();

        log.info("Extracting Db2 source DDL for {}.{} (type: {})", schema, objectName, objectType);

        String normalizedType = objectType == null ? "" : objectType.toUpperCase();

        return switch (normalizedType) {
            case "TABLE", "USER_TABLE" -> extractTableDdl(schema, objectName);
            case "VIEW" -> extractViewDdl(schema, objectName);
            case "TRIGGER" -> extractTriggerDdl(schema, objectName);
            case "INDEX" -> extractIndexDdl(schema, objectName);
            case "TYPE", "USER_DEFINED_TYPE" -> extractTypeDdl(schema, objectName);
            case "SEQUENCE" -> extractSequenceDdl(schema, objectName);
            default -> extractRoutineDdl(schema, objectName);
        };
    }

    /**
     * Builds a CREATE TABLE from SYSCAT.COLUMNS. LISTAGG assembles the column list in
     * COLNO order so the generated DDL matches the physical column order.
     */
    private String extractTableDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT 'CREATE TABLE %s.%s (' || CHR(10) || " +
            "LISTAGG('  ' || RTRIM(COLNAME) || ' ' || RTRIM(TYPENAME) || " +
            "CASE WHEN TYPENAME IN ('CHARACTER','VARCHAR','CHAR','GRAPHIC','VARGRAPHIC') " +
            "THEN '(' || RTRIM(CHAR(LENGTH)) || ')' " +
            "WHEN TYPENAME IN ('DECIMAL','NUMERIC') " +
            "THEN '(' || RTRIM(CHAR(LENGTH)) || ',' || RTRIM(CHAR(SCALE)) || ')' " +
            "ELSE '' END || " +
            "CASE WHEN DEFAULT IS NOT NULL THEN ' DEFAULT ' || RTRIM(DEFAULT) ELSE '' END || " +
            "CASE WHEN NULLS = 'N' THEN ' NOT NULL' ELSE '' END, " +
            "',' || CHR(10)) WITHIN GROUP (ORDER BY COLNO) || CHR(10) || ');' " +
            "FROM SYSCAT.COLUMNS " +
            "WHERE TABSCHEMA = '%s' AND TABNAME = '%s'",
            schema, objectName, upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    private String extractViewDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT TEXT FROM SYSCAT.VIEWS WHERE VIEWSCHEMA = '%s' AND VIEWNAME = '%s'",
            upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    private String extractTriggerDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT TEXT FROM SYSCAT.TRIGGERS WHERE TRIGSCHEMA = '%s' AND TRIGNAME = '%s'",
            upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    /**
     * Procedures and functions both live in SYSCAT.ROUTINES. The TEXT column holds the
     * full CREATE statement as submitted, so no reconstruction is needed. Ordering by
     * SPECIFICNAME keeps overloaded routines in a stable order.
     */
    private String extractRoutineDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT TEXT FROM SYSCAT.ROUTINES " +
            "WHERE ROUTINESCHEMA = '%s' AND ROUTINENAME = '%s' " +
            "ORDER BY SPECIFICNAME",
            upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    private String extractIndexDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT 'CREATE ' || CASE WHEN i.UNIQUERULE = 'U' THEN 'UNIQUE ' " +
            "WHEN i.UNIQUERULE = 'P' THEN 'UNIQUE ' ELSE '' END || 'INDEX ' || " +
            "RTRIM(i.INDSCHEMA) || '.' || RTRIM(i.INDNAME) || ' ON ' || " +
            "RTRIM(i.TABSCHEMA) || '.' || RTRIM(i.TABNAME) || ' (' || " +
            "LISTAGG(RTRIM(ic.COLNAME) || CASE WHEN ic.COLORDER = 'D' THEN ' DESC' ELSE ' ASC' END, ', ') " +
            "WITHIN GROUP (ORDER BY ic.COLSEQ) || ');' " +
            "FROM SYSCAT.INDEXES i " +
            "JOIN SYSCAT.INDEXCOLUSE ic ON i.INDSCHEMA = ic.INDSCHEMA AND i.INDNAME = ic.INDNAME " +
            "WHERE i.INDSCHEMA = '%s' AND i.INDNAME = '%s' " +
            "GROUP BY i.INDSCHEMA, i.INDNAME, i.TABSCHEMA, i.TABNAME, i.UNIQUERULE",
            upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    private String extractTypeDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT 'CREATE DISTINCT TYPE ' || RTRIM(TYPESCHEMA) || '.' || RTRIM(TYPENAME) || " +
            "' AS ' || RTRIM(SOURCETYPENAME) || " +
            "CASE WHEN SOURCETYPENAME IN ('CHARACTER','VARCHAR','CHAR','GRAPHIC','VARGRAPHIC') " +
            "THEN '(' || RTRIM(CHAR(LENGTH)) || ')' " +
            "WHEN SOURCETYPENAME IN ('DECIMAL','NUMERIC') " +
            "THEN '(' || RTRIM(CHAR(LENGTH)) || ',' || RTRIM(CHAR(SCALE)) || ')' " +
            "ELSE '' END || ' WITH COMPARISONS;' " +
            "FROM SYSCAT.DATATYPES " +
            "WHERE TYPESCHEMA = '%s' AND TYPENAME = '%s' AND METATYPE = 'T'",
            upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    private String extractSequenceDdl(String schema, String objectName) throws Exception {
        String sql = String.format(
            "SELECT 'CREATE SEQUENCE ' || RTRIM(SEQSCHEMA) || '.' || RTRIM(SEQNAME) || " +
            "' START WITH ' || RTRIM(CHAR(START)) || " +
            "' INCREMENT BY ' || RTRIM(CHAR(INCREMENT)) || " +
            "' MINVALUE ' || RTRIM(CHAR(MINVALUE)) || " +
            "' MAXVALUE ' || RTRIM(CHAR(MAXVALUE)) || " +
            "CASE WHEN CYCLE = 'Y' THEN ' CYCLE' ELSE ' NO CYCLE' END || ';' " +
            "FROM SYSCAT.SEQUENCES " +
            "WHERE SEQSCHEMA = '%s' AND SEQNAME = '%s'",
            upper(schema), upper(objectName)
        );

        return runQuery(schema, sql);
    }

    private String runQuery(String schema, String sql) throws Exception {
        log.info("Db2 DDL extraction SQL: {}", sql);
        var result = dbConnectionService.executeDb2Query(schema, sql);
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
        return (String) result.get("query_output");
    }

    /**
     * Reads dependencies from SYSCAT.ROUTINEDEP, SYSCAT.VIEWDEP and SYSCAT.TRIGDEP.
     * Db2 splits dependency tracking across one view per dependent object kind, so the
     * three are unioned into the shape the Dependency entity expects.
     *
     * <p>BTYPE is a single-character code; it is expanded to the readable object type
     * names the rest of the application uses.
     */
    public List<Dependency> getSourceDependencies(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        String schema = upper(obj.getSourceSchemaName());
        String name = upper(obj.getSourceObjectName());

        String btypeCase =
            "CASE d.BTYPE WHEN 'T' THEN 'TABLE' WHEN 'V' THEN 'VIEW' " +
            "WHEN 'F' THEN 'FUNCTION' WHEN 'P' THEN 'PROCEDURE' " +
            "WHEN 'I' THEN 'INDEX' WHEN 'S' THEN 'MAT_VIEW' " +
            "WHEN 'A' THEN 'ALIAS' WHEN 'N' THEN 'NICKNAME' " +
            "WHEN 'Q' THEN 'SEQUENCE' WHEN 'R' THEN 'USER_DEFINED_TYPE' " +
            "ELSE d.BTYPE END";

        String sql = String.format(
            "SELECT RTRIM(d.ROUTINESCHEMA), RTRIM(d.ROUTINENAME), 'ROUTINE', " +
            "RTRIM(d.BSCHEMA), RTRIM(d.BNAME), %s " +
            "FROM SYSCAT.ROUTINEDEP d " +
            "WHERE d.ROUTINESCHEMA = '%s' AND d.ROUTINENAME = '%s' " +
            "UNION ALL " +
            "SELECT RTRIM(d.VIEWSCHEMA), RTRIM(d.VIEWNAME), 'VIEW', " +
            "RTRIM(d.BSCHEMA), RTRIM(d.BNAME), %s " +
            "FROM SYSCAT.VIEWDEP d " +
            "WHERE d.VIEWSCHEMA = '%s' AND d.VIEWNAME = '%s' " +
            "UNION ALL " +
            "SELECT RTRIM(d.TRIGSCHEMA), RTRIM(d.TRIGNAME), 'TRIGGER', " +
            "RTRIM(d.BSCHEMA), RTRIM(d.BNAME), %s " +
            "FROM SYSCAT.TRIGDEP d " +
            "WHERE d.TRIGSCHEMA = '%s' AND d.TRIGNAME = '%s'",
            btypeCase, schema, name,
            btypeCase, schema, name,
            btypeCase, schema, name
        );

        log.info("Fetching Db2 dependencies for {}.{}", obj.getSourceSchemaName(), obj.getSourceObjectName());
        var result = dbConnectionService.executeDb2Query(obj.getSourceSchemaName(), sql);

        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }

        String output = (String) result.get("query_output");
        List<Dependency> dependencies = new ArrayList<>();

        if (output != null && !output.isEmpty()) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                String[] parts = line.split(",");
                if (parts.length >= 6) {
                    Dependency dep = new Dependency();
                    dep.setOwner(parts[0].trim());
                    dep.setName(parts[1].trim());
                    dep.setType(parts[2].trim());
                    dep.setReferencedOwner(parts[3].trim());
                    dep.setReferencedName(parts[4].trim());
                    dep.setReferencedType(parts[5].trim());

                    if ("TABLE".equals(dep.getReferencedType())) {
                        dep.setBackupScript(String.format(
                            "CREATE TABLE %s.%s_BAK AS (SELECT * FROM %s.%s) WITH DATA;",
                            dep.getReferencedOwner(), dep.getReferencedName(),
                            dep.getReferencedOwner(), dep.getReferencedName()
                        ));
                    }

                    dependencies.add(dep);
                }
            }
        }

        return dependencies;
    }

    public boolean isDb2MetadataType(String metaType) {
        return getDb2MetadataTypes().contains(metaType);
    }

    /**
     * Check if the object belongs to a Db2 system schema. Db2 ships a large catalog and
     * monitoring surface under these schemas, none of which is ever migrated.
     */
    public boolean isDb2SystemObject(String schemaName) {
        if (schemaName == null) {
            return false;
        }
        String upper = schemaName.toUpperCase();
        return DB2_SYSTEM_SCHEMAS.contains(upper) || upper.startsWith("SYS");
    }

    private static final java.util.Set<String> DB2_SYSTEM_SCHEMAS = java.util.Set.of(
        "SYSIBM", "SYSCAT", "SYSSTAT", "SYSFUN", "SYSPROC", "SYSIBMADM",
        "SYSIBMINTERNAL", "SYSIBMTS", "SYSPUBLIC", "SYSTOOLS", "NULLID",
        "SQLJ", "DB2GSE", "DB2QP", "SYSMON"
    );

    /**
     * Get list of Db2 metadata/folder types that should be excluded. These carry no DDL
     * of their own and exist only to group objects in the DMS assessment output.
     */
    public List<String> getDb2MetadataTypes() {
        return List.of(
            "DATABASES", "SCHEMAS", "TABLES", "VIEWS", "PROCEDURES", "FUNCTIONS",
            "TRIGGERS", "INDICES", "INDEXES", "CONSTRAINTS", "TYPES", "SEQUENCES",
            "ALIASES", "NICKNAMES", "PACKAGES", "MODULES", "TABLESPACES",
            "BUFFERPOOLS", "MATERIALIZED QUERY TABLES", "MATERIALIZED VIEWS",
            "USER-DEFINED TYPES", "USER DEFINED TYPES", "FOREIGN KEYS", "PARTITIONS",
            "DATABASE", "SCHEMA", "CONNECTION", "SERVER",
            "SQL_SCALAR_FUNCTIONS", "SQL_TABLE_VALUED_FUNCTIONS",
            "USER_DEFINED_TYPES", "FOREIGN_KEYS", "MAT_VIEWS",
            "MATERIALIZED_QUERY_TABLES", "TABLE_SPACES", "BUFFER_POOLS"
        );
    }

    /**
     * Splits a Db2 script into individual statements.
     *
     * <p>Db2 compound SQL bodies contain semicolons that terminate inner statements
     * rather than the routine itself, so a CREATE PROCEDURE / FUNCTION / TRIGGER opens a
     * block that is only closed by a line consisting of the terminator alone. Scripts
     * conventionally use {@code @} for this (the db2 CLP {@code -td@} option), and a
     * standalone {@code /} is also accepted for scripts converted from Oracle.
     */
    public List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBlock = false;

        String[] lines = sql.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            String upper = trimmed.toUpperCase();

            if (!inBlock && (upper.startsWith("CREATE PROCEDURE") || upper.startsWith("CREATE OR REPLACE PROCEDURE") ||
                upper.startsWith("CREATE FUNCTION") || upper.startsWith("CREATE OR REPLACE FUNCTION") ||
                upper.startsWith("CREATE TRIGGER") || upper.startsWith("CREATE OR REPLACE TRIGGER") ||
                upper.startsWith("CREATE MODULE") || upper.startsWith("BEGIN"))) {
                inBlock = true;
            }

            // A line holding only the alternate terminator ends the current statement,
            // including a compound block.
            if (trimmed.equals("@") || trimmed.equals("/")) {
                addStatement(statements, current.toString());
                current = new StringBuilder();
                inBlock = false;
                continue;
            }

            current.append(line).append("\n");

            if (trimmed.endsWith(";") && !inBlock) {
                addStatement(statements, current.toString());
                current = new StringBuilder();
            }
        }

        addStatement(statements, current.toString());

        return statements;
    }

    private static void addStatement(List<String> statements, String raw) {
        String stmt = raw.trim();
        if (stmt.endsWith(";")) {
            stmt = stmt.substring(0, stmt.length() - 1).trim();
        }
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
