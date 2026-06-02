package com.mma.testmanager.service;

import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.Dependency;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sybase ASE-specific operations for DMS Schema Conversion.
 */
@Service
@Slf4j
public class SybaseCommonService {
    private final DatabaseConnectionService dbConnectionService;
    private final DatabaseObjectRepository objectRepository;

    public SybaseCommonService(@Lazy DatabaseConnectionService dbConnectionService,
                               DatabaseObjectRepository objectRepository) {
        this.dbConnectionService = dbConnectionService;
        this.objectRepository = objectRepository;
    }

    public String extractSourceDdl(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        String schema = obj.getSourceSchemaName();
        String objectName = obj.getSourceObjectName();
        String objectType = obj.getSourceObjectType();

        log.info("Extracting Sybase source DDL for {}.{} (type: {})", schema, objectName, objectType);

        // For tables, build CREATE TABLE from syscolumns
        if ("TABLE".equalsIgnoreCase(objectType) || "USER_TABLE".equalsIgnoreCase(objectType)) {
            String sql = String.format(
                "SELECT c.name + ' ' + t.name + " +
                "CASE WHEN t.name IN ('varchar','nvarchar','char','nchar','binary','varbinary') THEN '(' + CONVERT(VARCHAR,c.length) + ')' " +
                "WHEN t.name IN ('decimal','numeric') THEN '(' + CONVERT(VARCHAR,c.prec) + ',' + CONVERT(VARCHAR,c.scale) + ')' ELSE '' END + " +
                "CASE WHEN c.status & 8 = 8 THEN ' NULL' ELSE ' NOT NULL' END " +
                "FROM syscolumns c JOIN systypes t ON c.usertype = t.usertype " +
                "WHERE c.id = OBJECT_ID('%s.%s') ORDER BY c.colid",
                schema, objectName
            );

            var result = dbConnectionService.executeSybaseQuery(schema, sql);
            if ("FAILURE".equals(result.get("status"))) {
                throw new RuntimeException((String) result.get("error_message"));
            }
            String columns = (String) result.get("query_output");
            if (columns != null && !columns.isEmpty()) {
                String[] cols = columns.split("\n");
                return "CREATE TABLE " + schema + "." + objectName + " (\n  " +
                    String.join(",\n  ", cols) + "\n)";
            }
            return (String) result.get("query_output");
        }

        // For code objects (procedures, functions, views, triggers), use sp_helptext
        String sql = String.format("sp_helptext '%s.%s'", schema, objectName);
        var result = dbConnectionService.executeSybaseQuery(schema, sql);
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
        return (String) result.get("query_output");
    }

    public List<Dependency> getSourceDependencies(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();

        String sql = String.format(
            "SELECT u.name, o.name, o.type, " +
            "u2.name, o2.name, o2.type " +
            "FROM sysdepends d " +
            "JOIN sysobjects o ON d.id = o.id " +
            "JOIN sysusers u ON o.uid = u.uid " +
            "JOIN sysobjects o2 ON d.depid = o2.id " +
            "JOIN sysusers u2 ON o2.uid = u2.uid " +
            "WHERE u.name = '%s' AND o.name = '%s'",
            obj.getSourceSchemaName(), obj.getSourceObjectName()
        );

        var result = dbConnectionService.executeSybaseQuery(obj.getSourceSchemaName(), sql);
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
                    dependencies.add(dep);
                }
            }
        }

        return dependencies;
    }

    public boolean isSybaseMetadataType(String metaType) {
        return getSybaseMetadataTypes().contains(metaType);
    }

    /**
     * Check if the object is a Sybase ASE system catalog object.
     * These are internal system tables/views present in every database.
     */
    public boolean isSybaseSystemObject(String objectName) {
        return SYBASE_SYSTEM_OBJECTS.contains(objectName.toLowerCase());
    }

    private static final java.util.Set<String> SYBASE_SYSTEM_OBJECTS = java.util.Set.of(
        "sysalternates", "sysattributes", "syscolumns", "syscomments",
        "sysconstraints", "sysdams", "sysdepends", "sysencryptkeys",
        "sysgams", "sysindexes", "sysjars", "syskeys", "syslogs",
        "sysobjects", "syspartitionkeys", "syspartitions", "sysprocedures",
        "sysprotects", "sysqueryplans", "sysreferences", "sysroles",
        "syssegments", "syssequences", "sysslices", "sysstatistics",
        "systabstats", "systhresholds", "systypes", "sysusermessages",
        "sysusers", "sysxtypes"
    );

    public List<String> getSybaseMetadataTypes() {
        return List.of(
            "DATABASES", "SCHEMAS", "TABLES", "VIEWS", "PROCEDURES", "FUNCTIONS",
            "TRIGGERS", "INDICES", "CONSTRAINTS", "TYPES", "SEQUENCES",
            "USER-DEFINED TYPES", "DEFAULTS", "RULES",
            "DATABASE", "SCHEMA", "CONNECTION", "SERVER",
            "SCALAR-VALUED FUNCTIONS", "TABLE-VALUED FUNCTIONS",
            "MATERIALIZED VIEWS", "USER DEFINED TYPES",
            "FOREIGN KEYS", "PARTITIONS", "INDEXES",
            "SQL_SCALAR_FUNCTIONS", "SQL_TABLE_VALUED_FUNCTIONS",
            "USER_DEFINED_TYPES", "FOREIGN_KEYS", "MAT_VIEWS"
        );
    }

    public List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBlock = false;

        String[] lines = sql.split("\n");

        for (String line : lines) {
            String trimmed = line.trim().toUpperCase();

            if (!inBlock && (trimmed.startsWith("CREATE PROC") || trimmed.startsWith("CREATE FUNCTION") ||
                trimmed.startsWith("CREATE TRIGGER") || trimmed.startsWith("BEGIN"))) {
                inBlock = true;
            }

            // "go" is the batch separator in Sybase (same as SQL Server)
            if (trimmed.equals("GO")) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
                inBlock = false;
                continue;
            }

            current.append(line).append("\n");

            if (line.trim().endsWith(";") && !inBlock) {
                String stmt = current.toString().trim();
                if (stmt.endsWith(";")) {
                    stmt = stmt.substring(0, stmt.length() - 1).trim();
                }
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            if (remaining.endsWith(";")) {
                remaining = remaining.substring(0, remaining.length() - 1).trim();
            }
            statements.add(remaining);
        }

        return statements;
    }
}
