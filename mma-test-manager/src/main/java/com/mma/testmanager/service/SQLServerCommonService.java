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
 * SQL Server-specific operations for DMS Schema Conversion.
 */
@Service
@Slf4j
public class SQLServerCommonService {
    private final DatabaseConnectionService dbConnectionService;
    private final DatabaseObjectRepository objectRepository;
    
    public SQLServerCommonService(@Lazy DatabaseConnectionService dbConnectionService,
                                 DatabaseObjectRepository objectRepository) {
        this.dbConnectionService = dbConnectionService;
        this.objectRepository = objectRepository;
    }
    
    public String extractSourceDdl(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        String schema = obj.getSourceSchemaName();
        String objectName = obj.getSourceObjectName();
        String objectType = obj.getSourceObjectType();
        
        log.info("Extracting SQL Server source DDL for {}.{} (type: {})", schema, objectName, objectType);
        
        // For tables, use INFORMATION_SCHEMA to generate CREATE TABLE
        if ("TABLE".equalsIgnoreCase(objectType) || "USER_TABLE".equalsIgnoreCase(objectType)) {
            String sql = String.format(
                "SELECT 'CREATE TABLE [' + '%s' + '].[' + '%s' + '] (' + CHAR(13) + CHAR(10) + " +
                "STRING_AGG('  [' + COLUMN_NAME + '] ' + DATA_TYPE + " +
                "CASE WHEN CHARACTER_MAXIMUM_LENGTH IS NOT NULL THEN '(' + CAST(CHARACTER_MAXIMUM_LENGTH AS VARCHAR) + ')' ELSE '' END + " +
                "CASE WHEN IS_NULLABLE = 'NO' THEN ' NOT NULL' ELSE '' END, ',' + CHAR(13) + CHAR(10)) + " +
                "CHAR(13) + CHAR(10) + ');' " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' " +
                "GROUP BY TABLE_SCHEMA, TABLE_NAME",
                schema, objectName, schema, objectName
            );
            
            log.info("SQL Server DDL extraction SQL: {}", sql);
            var result = dbConnectionService.executeSqlServerQuery(schema, sql);
            if ("FAILURE".equals(result.get("status"))) {
                throw new RuntimeException((String) result.get("error_message"));
            }
            return (String) result.get("query_output");
        }
        
        // For CONSTRAINT
        if ("CONSTRAINT".equalsIgnoreCase(objectType)) {
            String sql = String.format(
                "SELECT 'ALTER TABLE [' + SCHEMA_NAME(t.schema_id) + '].[' + OBJECT_NAME(cc.parent_object_id) + '] ADD CONSTRAINT [' + cc.name + '] CHECK ' + cc.definition " +
                "FROM sys.check_constraints cc " +
                "JOIN sys.tables t ON cc.parent_object_id = t.object_id " +
                "WHERE cc.name = '%s' AND SCHEMA_NAME(t.schema_id) = '%s'",
                objectName, schema
            );
            
            log.info("SQL Server DDL extraction SQL: {}", sql);
            var result = dbConnectionService.executeSqlServerQuery(schema, sql);
            if ("FAILURE".equals(result.get("status"))) {
                throw new RuntimeException((String) result.get("error_message"));
            }
            return (String) result.get("query_output");
        }
        
        // For INDEX
        if ("INDEX".equalsIgnoreCase(objectType)) {
            String sql = String.format(
                "SELECT 'CREATE ' + CASE WHEN i.is_unique = 1 THEN 'UNIQUE ' ELSE '' END + i.type_desc COLLATE DATABASE_DEFAULT + ' INDEX [' + i.name + '] ON [' + SCHEMA_NAME(t.schema_id) + '].[' + t.name + '] (' + " +
                "STRING_AGG('[' + c.name + ']' + CASE WHEN ic.is_descending_key = 1 THEN ' DESC' ELSE ' ASC' END, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) + ')' " +
                "FROM sys.indexes i " +
                "JOIN sys.tables t ON i.object_id = t.object_id " +
                "JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id " +
                "JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id " +
                "WHERE i.name = '%s' AND SCHEMA_NAME(t.schema_id) = '%s' " +
                "GROUP BY i.name, i.is_unique, i.type_desc, SCHEMA_NAME(t.schema_id), t.name",
                objectName, schema
            );
            
            log.info("SQL Server DDL extraction SQL: {}", sql);
            var result = dbConnectionService.executeSqlServerQuery(schema, sql);
            if ("FAILURE".equals(result.get("status"))) {
                throw new RuntimeException((String) result.get("error_message"));
            }
            return (String) result.get("query_output");
        }
        
        // For TYPE (user-defined types)
        if ("TYPE".equalsIgnoreCase(objectType)) {
            String sql = String.format(
                "SELECT 'CREATE TYPE [' + SCHEMA_NAME(t.schema_id) + '].[' + t.name + '] FROM ' + st.name + " +
                "CASE WHEN st.name IN ('varchar', 'nvarchar', 'char', 'nchar') THEN '(' + CAST(t.max_length AS VARCHAR) + ')' " +
                "WHEN st.name IN ('decimal', 'numeric') THEN '(' + CAST(t.precision AS VARCHAR) + ',' + CAST(t.scale AS VARCHAR) + ')' ELSE '' END + " +
                "CASE WHEN t.is_nullable = 1 THEN ' NULL' ELSE ' NOT NULL' END " +
                "FROM sys.types t " +
                "JOIN sys.types st ON t.system_type_id = st.system_type_id " +
                "WHERE t.is_user_defined = 1 AND t.name = '%s' AND SCHEMA_NAME(t.schema_id) = '%s' AND st.is_user_defined = 0",
                objectName, schema
            );
            
            log.info("SQL Server DDL extraction SQL: {}", sql);
            var result = dbConnectionService.executeSqlServerQuery(schema, sql);
            if ("FAILURE".equals(result.get("status"))) {
                throw new RuntimeException((String) result.get("error_message"));
            }
            return (String) result.get("query_output");
        }
        
        // For code objects (procedures, functions, views), use OBJECT_DEFINITION
        String sql = String.format(
            "SELECT OBJECT_DEFINITION(OBJECT_ID('%s.%s'))",
            schema, objectName
        );
        
        log.info("SQL Server DDL extraction SQL: {}", sql);
        var result = dbConnectionService.executeSqlServerQuery(schema, sql);
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
        return (String) result.get("query_output");
    }
    
    public List<Dependency> getSourceDependencies(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        
        String sql = String.format(
            "SELECT OBJECT_SCHEMA_NAME(referencing_id) AS referencing_schema, " +
            "OBJECT_NAME(referencing_id) AS referencing_object, " +
            "o.type_desc AS referencing_type, " +
            "OBJECT_SCHEMA_NAME(referenced_id) AS referenced_schema, " +
            "OBJECT_NAME(referenced_id) AS referenced_object, " +
            "ro.type_desc AS referenced_type " +
            "FROM sys.sql_expression_dependencies sed " +
            "JOIN sys.objects o ON sed.referencing_id = o.object_id " +
            "LEFT JOIN sys.objects ro ON sed.referenced_id = ro.object_id " +
            "WHERE OBJECT_SCHEMA_NAME(referencing_id) = '%s' " +
            "AND OBJECT_NAME(referencing_id) = '%s' " +
            "ORDER BY referenced_type, referenced_schema, referenced_object",
            obj.getSourceSchemaName(), obj.getSourceObjectName()
        );
        
        log.info("Fetching SQL Server dependencies for {}.{}", obj.getSourceSchemaName(), obj.getSourceObjectName());
        var result = dbConnectionService.executeSqlServerQuery(obj.getSourceSchemaName(), sql);
        
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
                    dep.setType(parts[2].trim());
                    dep.setOwner(parts[0].trim());
                    dep.setName(parts[1].trim());
                    dep.setReferencedType(parts[5].trim());
                    dep.setReferencedOwner(parts[3].trim());
                    dep.setReferencedName(parts[4].trim());
                    
                    if ("USER_TABLE".equals(dep.getReferencedType())) {
                        dep.setBackupScript(String.format(
                            "SELECT * INTO %s.%s_%s FROM %s.%s;",
                            dep.getReferencedOwner(), dep.getReferencedName(), 
                            "' + FORMAT(GETDATE(), 'yyMMddHHmm') + '",
                            dep.getReferencedOwner(), dep.getReferencedName()
                        ));
                    }
                    
                    dependencies.add(dep);
                }
            }
        }
        
        return dependencies;
    }
    
    /**
     * Check if the given meta-type is a SQL Server metadata/folder type that should be skipped.
     * These types have no actual data and are just organizational structures.
     * Note: Folder types are plural (e.g., "SQL SCALAR FUNCTIONS"), actual objects are singular (e.g., "SQL_SCALAR_FUNCTION")
     */
    public boolean isSQLServerMetadataType(String metaType) {
        return getSQLServerMetadataTypes().contains(metaType);
    }
    
    /**
     * Get list of SQL Server metadata/folder types that should be excluded.
     */
    public List<String> getSQLServerMetadataTypes() {
        return List.of(
            "AGGREGATE FUNCTIONS", "CONSTRAINTS", "EXTERNAL TABLES", "GRAPH TABLES",
            "INDICES", "PARTITIONS", "SEQUENCES", "SQL INLINE FUNCTIONS",
            "SQL SCALAR FUNCTIONS", "SQL TABLE-VALUED FUNCTIONS", "SYNONYMS", "TABLE TYPES",
            "TRIGGERS", "USER-DEFINED TYPES", "XML SCHEMA COLLECTIONS", "ASSEMBLIES",
            "DATABASES", "SCHEMAS", "TYPES", "TABLES", "VIEWS", "PROCEDURES",
            "FUNCTIONS", "OPERATORS", "DOMAINS",
            // Additional metadata types to skip
            "CONNECTION", "DATABASE", "EXTERNAL_TABLES", "SCHEMA", "SERVER",
            "SQL_INLINE_FUNCTIONS", "SQL_SCALAR_FUNCTIONS", "SQL_TABLE_VALUED_FUNCTIONS",
            "TABLE_TYPES", "USER_DEFINED_TYPES", "XML_SCHEMA_COLLECTIONS",
            "AGGREGATE_FUNCTIONS"
        );
    }
    
    public List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBlock = false;
        boolean inSingleQuote = false;
        
        String[] lines = sql.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim().toUpperCase();
            
            if (!inBlock && (trimmed.startsWith("CREATE FUNCTION") || trimmed.startsWith("CREATE OR ALTER FUNCTION") ||
                trimmed.startsWith("CREATE PROCEDURE") || trimmed.startsWith("CREATE OR ALTER PROCEDURE") ||
                trimmed.startsWith("CREATE PROC") || trimmed.startsWith("CREATE OR ALTER PROC") ||
                trimmed.startsWith("BEGIN"))) {
                inBlock = true;
            }
            
            current.append(line).append("\n");
            
            for (char c : line.toCharArray()) {
                if (c == '\'') {
                    inSingleQuote = !inSingleQuote;
                }
            }
            
            if (trimmed.equals("GO")) {
                String stmt = current.toString().trim();
                stmt = stmt.substring(0, stmt.length() - 2).trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
                inBlock = false;
                continue;
            }
            
            if (line.trim().endsWith(";") && !inBlock && !inSingleQuote) {
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
