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
 * Oracle-specific database operations.
 */
@Service
@Slf4j
public class OracleCommonService {
    private final DatabaseConnectionService dbConnectionService;
    private final DatabaseObjectRepository objectRepository;
    
    public OracleCommonService(@Lazy DatabaseConnectionService dbConnectionService,
                              DatabaseObjectRepository objectRepository) {
        this.dbConnectionService = dbConnectionService;
        this.objectRepository = objectRepository;
    }

    public String extractSourceDdl(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        String objectType = obj.getSourceObjectType();
        String schema = obj.getSourceSchemaName();
        String objectName = obj.getSourceObjectName();
        String packageName = obj.getSourcePackageName();
        
        log.info("Extracting Oracle source DDL for {}.{} (type: {})", schema, objectName, objectType);
        
        // For package members, get full package spec and body
        if ((objectType.equals("PROCEDURE") || objectType.equals("FUNCTION")) && packageName != null && !packageName.isEmpty()) {
            StringBuilder result = new StringBuilder();
            
            // Get package spec
            String specSql = String.format("SELECT DBMS_METADATA.GET_DDL('PACKAGE', '%s', '%s') FROM DUAL", packageName, schema);
            log.info("Oracle DDL extraction SQL: {}", specSql);
            var specResult = dbConnectionService.executeOracleQuery(schema, specSql);
            if ("FAILURE".equals(specResult.get("status"))) {
                throw new RuntimeException("Failed to get package spec: " + specResult.get("error_message"));
            }
            result.append(specResult.get("query_output")).append("\n\n");
            
            // Get package body
            String bodySql = String.format("SELECT DBMS_METADATA.GET_DDL('PACKAGE_BODY', '%s', '%s') FROM DUAL", packageName, schema);
            log.info("Oracle DDL extraction SQL: {}", bodySql);
            var bodyResult = dbConnectionService.executeOracleQuery(schema, bodySql);
            if ("FAILURE".equals(bodyResult.get("status"))) {
                throw new RuntimeException("Failed to get package body: " + bodyResult.get("error_message"));
            }
            result.append(bodyResult.get("query_output"));
            
            return result.toString().trim();
        }
        
        // Standalone object
        String sql = String.format(
            "SELECT DBMS_METADATA.GET_DDL('%s', '%s', '%s') FROM DUAL",
            objectType.toUpperCase(),
            objectName.toUpperCase(),
            schema.toUpperCase()
        );
        
        log.info("Oracle DDL extraction SQL: {}", sql);
        var result = dbConnectionService.executeOracleQuery(schema, sql);
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
        return (String) result.get("query_output");
    }

    public List<Dependency> getSourceDependencies(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        
        // For package members, query dependencies of the package
        String queryName = obj.getSourcePackageName() != null && !obj.getSourcePackageName().isEmpty() 
            ? obj.getSourcePackageName() 
            : obj.getSourceObjectName();
        
        String queryType = obj.getSourcePackageName() != null && !obj.getSourcePackageName().isEmpty()
            ? "PACKAGE BODY"
            : obj.getSourceObjectType();
        
        String sql = String.format(
            "SELECT type, owner, name, referenced_type, referenced_owner, referenced_name " +
            "FROM all_dependencies " +
            "WHERE owner = '%s' AND name = '%s' AND type = '%s' " +
            "ORDER BY referenced_type, referenced_owner, referenced_name",
            obj.getSourceSchemaName(), queryName, queryType
        );
        
        log.info("Fetching Oracle dependencies for {}.{}", obj.getSourceSchemaName(), queryName);
        var result = dbConnectionService.executeOracleQuery(obj.getSourceSchemaName(), sql);
        
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
                    dep.setType(parts[0].trim());
                    dep.setOwner(parts[1].trim());
                    dep.setName(parts[2].trim());
                    dep.setReferencedType(parts[3].trim());
                    dep.setReferencedOwner(parts[4].trim());
                    dep.setReferencedName(parts[5].trim());
                    
                    if ("TABLE".equals(dep.getReferencedType())) {
                        dep.setBackupScript(String.format(
                            "CREATE TABLE %s.%s_' || TO_CHAR(SYSDATE, 'YYMMDDHH24MI') || ' AS SELECT * FROM %s.%s;",
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
    
    /**
     * Check if the given meta-type is an Oracle metadata/folder type that should be skipped.
     * These types have no actual data and are just organizational structures.
     */
    public boolean isOracleMetadataType(String metaType) {
        return getOracleMetadataTypes().contains(metaType);
    }
    
    /**
     * Get list of Oracle metadata/folder types that should be excluded.
     */
    public List<String> getOracleMetadataTypes() {
        return List.of(
            "CLUSTER_KEYS", "CLUSTERS", "COLLECTION_TYPES", "CONNECTIONS", "CONSTRAINTS", 
            "DATABASE_LINKS", "DBMS_JOBS", "EXTERNAL_TABLES", "FUNCTIONS", "INDICES", 
            "LARGE_OBJECTS", "MAT_VIEWS", "MATERIALIZED_VIEW_LOGS", "NESTED_TABLES", 
            "PARTITIONS", "PACKAGES", "PRIVATE_COLLECTION_TYPES", "PRIVATE_CONSTANTS", 
            "PRIVATE_CURSORS", "PRIVATE_EXCEPTIONS", "PRIVATE_FUNCTIONS", "PRIVATE_PROCEDURES", 
            "PRIVATE_TYPES", "PRIVATE_VARIABLES", "PROCEDURES", "PROGRAMS", 
            "PUBLIC_COLLECTION_TYPES", "PUBLIC_CONSTANTS", "PUBLIC_CURSORS", "PUBLIC_EXCEPTIONS", 
            "PUBLIC_FUNCTIONS", "PUBLIC_PROCEDURES", "PUBLIC_TYPES", "PUBLIC_VARIABLES", 
            "SCHEDULES", "SCHEMAS", "SEQUENCES", "SERVER", "SYNONYMS", "TABLES", "TRIGGERS",
            "USER_DEFINED_TYPES", "VIEWS"
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
            
            if (!inBlock && (trimmed.startsWith("CREATE FUNCTION") || trimmed.startsWith("CREATE OR REPLACE FUNCTION") ||
                trimmed.startsWith("CREATE PROCEDURE") || trimmed.startsWith("CREATE OR REPLACE PROCEDURE") ||
                trimmed.startsWith("CREATE PACKAGE") || trimmed.startsWith("CREATE OR REPLACE PACKAGE") ||
                trimmed.startsWith("BEGIN") || trimmed.startsWith("DECLARE"))) {
                inBlock = true;
            }
            
            current.append(line).append("\n");
            
            for (char c : line.toCharArray()) {
                if (c == '\'') {
                    inSingleQuote = !inSingleQuote;
                }
            }
            
            if (inBlock && (trimmed.equals("END;") || trimmed.equals("/"))) {
                inBlock = false;
                String stmt = current.toString().trim();
                if (stmt.endsWith(";")) {
                    stmt = stmt.substring(0, stmt.length() - 1).trim();
                }
                if (stmt.endsWith("/")) {
                    stmt = stmt.substring(0, stmt.length() - 1).trim();
                }
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
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
            if (remaining.endsWith("/")) {
                remaining = remaining.substring(0, remaining.length() - 1).trim();
            }
            statements.add(remaining);
        }
        
        return statements;
    }
}
