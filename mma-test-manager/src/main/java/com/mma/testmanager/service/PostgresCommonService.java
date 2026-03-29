package com.mma.testmanager.service;

import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL-specific SQL generation and utilities.
 */
@Service
@Slf4j
public class PostgresCommonService {
    private final DatabaseConnectionService dbConnectionService;
    private final DatabaseObjectRepository objectRepository;
    
    public PostgresCommonService(@Lazy DatabaseConnectionService dbConnectionService,
                                DatabaseObjectRepository objectRepository) {
        this.dbConnectionService = dbConnectionService;
        this.objectRepository = objectRepository;
    }
    
    public String extractTargetDdl(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        String objectType = obj.getTargetObjectType() != null ? obj.getTargetObjectType() : obj.getSourceObjectType();
        String schema = obj.getTargetSchemaName();
        String name = obj.getTargetObjectName();
        
        log.info("Extracting PostgreSQL target DDL for {}.{} (type: {})", schema, name, objectType);
        
        String sql = buildExtractDdlSql(objectType, schema, name);
        var result = dbConnectionService.executePostgresQuery(schema, sql);
        
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
        return (String) result.get("query_output");
    }

    public String buildExtractDdlSql(String objectType, String schema, String name) {
        String sql;
        if ("TABLE".equalsIgnoreCase(objectType) || "USER_TABLE".equalsIgnoreCase(objectType)) {
            sql = String.format(
                "SELECT 'CREATE TABLE %s.%s (' || E'\\n' || " +
                "string_agg('  ' || column_name || ' ' || data_type || " +
                "CASE WHEN character_maximum_length IS NOT NULL THEN '(' || character_maximum_length || ')' ELSE '' END || " +
                "CASE WHEN column_default IS NOT NULL THEN ' DEFAULT ' || column_default ELSE '' END || " +
                "CASE WHEN is_nullable = 'NO' THEN ' NOT NULL' ELSE '' END, ',' || E'\\n' ORDER BY ordinal_position) || " +
                "E'\\n);' " +
                "FROM information_schema.columns " +
                "WHERE table_schema = '%s' AND table_name = '%s'",
                schema, name, schema, name
            );
        } else if ("VIEW".equalsIgnoreCase(objectType)) {
            sql = String.format(
                "SELECT 'CREATE OR REPLACE VIEW %s.%s AS' || E'\\n' || pg_get_viewdef('%s.%s'::regclass, true)",
                schema, name, schema, name
            );
        } else if ("CONSTRAINT".equalsIgnoreCase(objectType)) {
            sql = String.format(
                "SELECT 'ALTER TABLE ' || n.nspname || '.' || c.relname || E'\\n' || " +
                "'ADD CONSTRAINT ' || con.conname || ' ' || pg_get_constraintdef(con.oid) || ';' " +
                "FROM pg_constraint con " +
                "JOIN pg_class c ON con.conrelid = c.oid " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE con.conname = '%s' AND n.nspname = '%s'",
                name, schema
            );
        } else if ("INDEX".equalsIgnoreCase(objectType)) {
            sql = String.format(
                "SELECT pg_get_indexdef(i.indexrelid) || ';' " +
                "FROM pg_index i " +
                "JOIN pg_class c ON i.indexrelid = c.oid " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE c.relname = '%s' AND n.nspname = '%s'",
                name, schema
            );
        } else if ("DOMAIN".equalsIgnoreCase(objectType) || "TYPE".equalsIgnoreCase(objectType)) {
            sql = String.format(
                "SELECT 'CREATE DOMAIN ' || n.nspname || '.' || t.typname || ' AS ' || " +
                "pg_catalog.format_type(t.typbasetype, t.typtypmod) || " +
                "CASE WHEN t.typnotnull THEN ' NOT NULL' ELSE '' END " +
                "FROM pg_type t " +
                "JOIN pg_namespace n ON t.typnamespace = n.oid " +
                "WHERE t.typtype = 'd' AND n.nspname = '%s' AND t.typname = '%s'",
                schema, name
            );
        } else {
            sql = String.format(
                "SELECT pg_get_functiondef(p.oid) FROM pg_proc p " +
                "JOIN pg_namespace n ON p.pronamespace = n.oid " +
                "WHERE LOWER(p.proname) = LOWER('%s') AND n.nspname = '%s'",
                name, schema
            );
        }
        log.info("Generated DDL extraction SQL for {} {}.{}: {}", objectType, schema, name, sql);
        return sql;
    }
    
    public List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        String dollarTag = null;
        boolean inSingleQuote = false;
        boolean inBlock = false;
        
        String[] lines = sql.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim().toUpperCase();
            
            if (!inBlock && (trimmed.startsWith("CREATE FUNCTION") || trimmed.startsWith("CREATE OR REPLACE FUNCTION") ||
                trimmed.startsWith("CREATE PROCEDURE") || trimmed.startsWith("CREATE OR REPLACE PROCEDURE") ||
                trimmed.startsWith("DO $$") || trimmed.startsWith("DO $"))) {
                inBlock = true;
            }
            
            current.append(line).append("\n");
            
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                
                if (c == '\'' && !inDollarQuote) {
                    inSingleQuote = !inSingleQuote;
                } else if (c == '$' && !inSingleQuote) {
                    int endIdx = line.indexOf('$', i + 1);
                    if (endIdx > i) {
                        String tag = line.substring(i, endIdx + 1);
                        if (inDollarQuote && tag.equals(dollarTag)) {
                            inDollarQuote = false;
                            dollarTag = null;
                        } else if (!inDollarQuote) {
                            inDollarQuote = true;
                            dollarTag = tag;
                        }
                        i = endIdx;
                    }
                }
            }
            
            if (line.trim().endsWith(";") && !inDollarQuote && !inSingleQuote) {
                if (inBlock) {
                    inBlock = false;
                }
                
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
