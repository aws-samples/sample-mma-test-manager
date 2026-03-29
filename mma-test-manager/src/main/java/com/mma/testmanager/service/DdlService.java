package com.mma.testmanager.service;

import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.Project;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DdlService {
    private final DatabaseObjectRepository repository;
    private final DatabaseConnectionService dbService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final com.mma.testmanager.repository.ProjectRepository projectRepository;
    private final KiroCliCommonService kiroCliService;
    private final OracleCommonService oracleService;
    private final PostgresCommonService postgresqlService;
    private final SQLServerCommonService sqlServerService;

    @Transactional
    public void retrieveSourceDdlFromDb(Long objectId) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        try {
            log.info("Retrieving source DDL from DB - Schema: {}, Object: {}, Type: {}", 
                obj.getSourceSchemaName(), obj.getSourceObjectName(), obj.getSourceObjectType());
            String ddl = extractSourceDdlFromDatabase(objectId);
            log.info("Retrieved DDL:\n{}", ddl);
            obj.setSourceDdlFromDb(ddl);
            repository.save(obj);
        } catch (Exception e) {
            log.error("Error retrieving source DDL from DB", e);
            throw new RuntimeException("Failed to retrieve source DDL: " + e.getMessage());
        }
    }

    @Transactional
    public void retrieveTargetDdlFromDb(Long objectId) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        try {
            log.info("Retrieving target DDL from DB - Schema: {}, Object: {}, Type: {}", 
                obj.getTargetSchemaName(), obj.getTargetObjectName(), obj.getSourceObjectType());
            String ddl = extractTargetDdlFromDatabase(objectId);
            log.info("Retrieved DDL:\n{}", ddl);
            obj.setTargetDdlFromDb(ddl);
            DatabaseObject saved = repository.save(obj);
            log.info("Saved targetDdlFromDb for object {} (length: {})", saved.getId(), 
                saved.getTargetDdlFromDb() != null ? saved.getTargetDdlFromDb().length() : 0);
        } catch (Exception e) {
            log.error("Error retrieving target DDL from DB", e);
            throw new RuntimeException("Failed to retrieve target DDL: " + e.getMessage());
        }
    }
    
    private String extractSourceDdlFromDatabase(Long objectId) throws Exception {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        Project project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = project.getSourceEndpoint().getDbEngine();
        
        if ("oracle".equalsIgnoreCase(sourceEngine)) {
            return oracleService.extractSourceDdl(objectId);
        } else if ("sqlserver".equalsIgnoreCase(sourceEngine)) {
            return sqlServerService.extractSourceDdl(objectId);
        }
        
        throw new UnsupportedOperationException("Source database engine not supported: " + sourceEngine);
    }
    
    private String extractTargetDdlFromDatabase(Long objectId) throws Exception {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        Project project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String targetEngine = project.getTargetEndpoint().getDbEngine();
        
        if ("postgres".equalsIgnoreCase(targetEngine) || "postgresql".equalsIgnoreCase(targetEngine)) {
            return postgresqlService.extractTargetDdl(objectId);
        }
        
        throw new UnsupportedOperationException("Target database engine not supported: " + targetEngine);
    }

    @Transactional
    public void saveSourceUserOverwrite(Long objectId, String ddl) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        obj.setSourceDdlUserOverwrite(ddl);
        repository.save(obj);
    }

    @Transactional
    public void saveTargetUserOverwrite(Long objectId, String ddl) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        obj.setTargetDdlUserOverwrite(ddl);
        repository.save(obj);
    }

    @Transactional
    public void convertDdl(Long objectId, String sourceDdl, String additionalInstruction) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        
        try {
            String prompt = buildConversionPrompt(obj, sourceDdl, additionalInstruction);
            String rawOutput = kiroCliService.execute(prompt);
            String convertedDdl = kiroCliService.extractCodeFromSQLMarker(rawOutput, "CREATE", true);
            
            obj.setTargetDdlConverted(convertedDdl);
            repository.save(obj);
        } catch (Exception e) {
            log.error("Error converting DDL", e);
            throw new RuntimeException("Failed to convert DDL: " + e.getMessage());
        }
    }

    public void applyToTargetDatabase(Long objectId, String ddl, boolean dropBeforeRecreate) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        
        try {
            String schema = obj.getTargetSchemaName();
            String objectName = obj.getTargetObjectName();
            log.info("Applying DDL to target database - Schema: {}, Object: {}, Drop before recreate: {}", 
                schema, objectName, dropBeforeRecreate);
            
            // If drop before recreate is enabled, generate and execute DROP statement
            if (dropBeforeRecreate && objectName != null && !objectName.isEmpty()) {
                String dropDdl = generateDropStatement(obj);
                if (dropDdl != null) {
                    log.info("Dropping existing object:\n{}", dropDdl);
                    try {
                        dbService.executePostgreSQLSql(schema, dropDdl);
                        log.info("Successfully dropped existing object");
                    } catch (Exception e) {
                        log.warn("Failed to drop object (may not exist): {}", e.getMessage());
                        // Continue with CREATE even if DROP fails (object might not exist)
                    }
                }
            }
            
            log.info("DDL to apply:\n{}", ddl);
            dbService.executePostgreSQLSql(schema, ddl);
            
            // Refresh target DDL from database after successful deployment
            log.info("Refreshing target DDL from database for object {}", objectId);
            try {
                // Re-fetch the object by name in case ID changed
                DatabaseObject refreshedObj = repository.findById(objectId).orElse(null);
                if (refreshedObj != null) {
                    String targetSchema = refreshedObj.getTargetSchemaName();
                    String targetName = refreshedObj.getTargetObjectName();
                    String projectId = refreshedObj.getProjectId();
                    
                    // Find object by name and project
                    DatabaseObject currentObj = repository.findAll().stream()
                        .filter(o -> o.getProjectId().equals(projectId) &&
                                   targetSchema.equals(o.getTargetSchemaName()) &&
                                   targetName.equals(o.getTargetObjectName()))
                        .findFirst()
                        .orElse(refreshedObj);
                    
                    retrieveTargetDdlFromDb(currentObj.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve DDL after deployment (object may not exist or DDL was invalid): {}", e.getMessage());
                // Don't fail the entire operation if we can't retrieve the DDL back
            }
            
            log.info("Successfully applied DDL to target database for object {}", objectId);
        } catch (Exception e) {
            log.error("Error applying DDL to target database", e);
            throw new RuntimeException("Failed to apply DDL: " + e.getMessage());
        }
    }
    
    private String generateDropStatement(DatabaseObject obj) {
        String objectType = obj.getTargetObjectType() != null ? obj.getTargetObjectType() : obj.getSourceObjectType();
        String schema = obj.getTargetSchemaName();
        String name = obj.getTargetObjectName();
        
        if (objectType == null || schema == null || name == null) {
            return null;
        }
        
        String fullName = schema + "." + name;
        
        // Generate DROP statement based on object type (without CASCADE to prevent unintended drops)
        return switch (objectType.toUpperCase()) {
            case "PROCEDURE", "SQL_STORED_PROCEDURE" -> "DROP PROCEDURE IF EXISTS " + fullName;
            case "FUNCTION", "SQL_SCALAR_FUNCTION", "SQL_INLINE_FUNCTION", "SQL_TABLE_VALUED_FUNCTION" -> 
                "DROP FUNCTION IF EXISTS " + fullName;
            case "VIEW" -> "DROP VIEW IF EXISTS " + fullName;
            case "TRIGGER" -> "DROP TRIGGER IF EXISTS " + name + " ON " + schema + ".<table_name>"; // Note: needs table name
            case "TABLE", "USER_TABLE" -> "DROP TABLE IF EXISTS " + fullName;
            case "TYPE" -> "DROP TYPE IF EXISTS " + fullName;
            default -> null;
        };
    }


    private String buildConversionPrompt(DatabaseObject obj, String sourceDdl, String additionalInstruction) {
        StringBuilder prompt = new StringBuilder();
        
        // Get knowledge base content and database engine info
        var project = projectRepository.findById(obj.getProjectId()).orElse(null);
        String sourceEngine = "Oracle"; // default fallback
        String targetEngine = "PostgreSQL"; // default fallback
        
        if (project != null) {
            String kbContent = knowledgeBaseService.getKnowledgeBaseContent(project, "DDL_CONVERSION");
            if (!kbContent.isEmpty()) {
                prompt.append("Knowledge Base:\n").append(kbContent).append("\n\n");
            }
            
            // Get actual database engines
            if (project.getSourceDbEngine() != null) {
                sourceEngine = capitalizeDbEngine(project.getSourceDbEngine());
            }
            if (project.getTargetDbEngine() != null) {
                targetEngine = capitalizeDbEngine(project.getTargetDbEngine());
            }
        }
        
        prompt.append("Convert the following ").append(sourceEngine).append(" DDL to ").append(targetEngine).append(" DDL.\n\n");
        prompt.append("Object Type: ").append(obj.getSourceObjectType()).append("\n");
        prompt.append("Source Schema: ").append(obj.getSourceSchemaName()).append("\n");
        prompt.append("Target Schema: ").append(obj.getTargetSchemaName()).append("\n");
        prompt.append("Object Name: ").append(obj.getSourceObjectName()).append("\n\n");
        
        prompt.append("Source DDL (").append(sourceEngine).append("):\n");
        prompt.append("```sql\n");
        prompt.append(sourceDdl);
        prompt.append("\n```\n\n");
        
        // Include existing target DDL as reference if available
        String existingTargetDdl = obj.getTargetDdlFromDb() != null ? obj.getTargetDdlFromDb() : obj.getTargetDdl();
        if (existingTargetDdl != null && !existingTargetDdl.trim().isEmpty()) {
            prompt.append("Existing Target DDL (").append(targetEngine).append(") for reference:\n");
            prompt.append("```sql\n");
            prompt.append(existingTargetDdl);
            prompt.append("\n```\n\n");
        }
        
        if (additionalInstruction != null && !additionalInstruction.trim().isEmpty()) {
            prompt.append("Additional Instructions:\n");
            prompt.append(additionalInstruction.trim());
            prompt.append("\n\n");
        }
        
        prompt.append("CRITICAL: Use the Target Schema (").append(obj.getTargetSchemaName()).append(") in the converted DDL, NOT the Source Schema.\n");
        prompt.append("CRITICAL: Preserve ALL operators including * (multiplication), +, -, /, etc.\n");
        prompt.append("Please provide only the converted ").append(targetEngine).append(" DDL without explanations.");
        
        return prompt.toString();
    }
    
    private String capitalizeDbEngine(String dbEngine) {
        if (dbEngine == null) return "Unknown";
        return switch (dbEngine.toLowerCase()) {
            case "oracle" -> "Oracle";
            case "postgres", "postgresql" -> "PostgreSQL";
            case "sqlserver" -> "SQL Server";
            case "mysql" -> "MySQL";
            default -> dbEngine.substring(0, 1).toUpperCase() + dbEngine.substring(1);
        };
    }

}
