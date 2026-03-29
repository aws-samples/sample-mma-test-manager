package com.mma.testmanager.service;

import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.TestCase;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import com.mma.testmanager.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service for comparison testing between source and target databases.
 * Supports Oracle-to-PostgreSQL and can be extended for other database pairs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComparisonTestService {
    private final TestCaseRepository testCaseRepository;
    private final DatabaseObjectRepository objectRepository;
    private final DatabaseConnectionService dbConnectionService;
    private final TestCaseBaseService baseService;
    private final KiroCliCommonService kiroCliCommonService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final com.mma.testmanager.repository.ProjectRepository projectRepository;
    private final DdlService ddlService;
    
    public void updateTestCase(Long testCaseId, String sourceSql, String targetSql) {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        tc.setSourceSql(sourceSql);
        tc.setTargetSql(targetSql);
        testCaseRepository.save(tc);
        log.info("Updated test case {}", testCaseId);
    }
    
    public String fixWithGenAI(Long testCaseId, String dbType, String additionalInstructions) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        // Get actual engine names from project
        var project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = project.getSourceEndpoint().getDbEngine();
        String targetEngine = project.getTargetEndpoint().getDbEngine();
        
        log.info("Fix test case - dbType: {}, sourceEngine: {}, targetEngine: {}", dbType, sourceEngine, targetEngine);
        
        // Check if fixing source or target (case-insensitive match)
        boolean isSource = sourceEngine.equalsIgnoreCase(dbType);
        String actualEngine = isSource ? sourceEngine : targetEngine;
        
        log.info("isSource: {}, actualEngine: {}", isSource, actualEngine);
        
        String currentSql = isSource ? tc.getSourceSql() : tc.getTargetSql();
        String errorMsg = isSource ? tc.getSourceError() : tc.getTargetError();
        String ddl = getBestAvailableDdl(obj, !isSource);
        String schemaName = isSource ? obj.getSourceSchemaName() : obj.getTargetSchemaName();
        String objectName = isSource ? obj.getSourceObjectName() : obj.getTargetObjectName();
        
        String fullName = schemaName + ".";
        if (obj.getSourcePackageName() != null && !obj.getSourcePackageName().isEmpty()) {
            fullName += obj.getSourcePackageName() + ".";
        }
        fullName += objectName;
        
        // Get knowledge base content
        String kbContent = "";
        if (project != null) {
            kbContent = knowledgeBaseService.getKnowledgeBaseContent(project, "COMPARISON_TEST");
            if (!kbContent.isEmpty()) {
                kbContent = "Knowledge Base:\n" + kbContent + "\n\n";
            }
        }
        
        String prompt = String.format(
            "%s" +
            "Fix this %s test case that failed with an error.\n\n" +
            "Object: %s %s\n" +
            "Test Description: %s\n" +
            "Test Scenario: %s\n\n" +
            "Current SQL:\n%s\n\n" +
            "Error Message:\n%s\n\n" +
            "DDL:\n%s\n\n" +
            "%s" +
            "Generate a corrected SQL statement that fixes the error.\n" +
            "IMPORTANT: Return ONLY the corrected SQL wrapped in a code block.\n" +
            "Use triple backticks with sql language identifier:\n" +
            "```sql\n" +
            "your corrected sql here\n" +
            "```",
            kbContent,
            actualEngine, obj.getSourceObjectType(), fullName, tc.getDescription(), tc.getScenario(),
            currentSql, errorMsg, ddl,
            (additionalInstructions != null && !additionalInstructions.isEmpty()) 
                ? "Additional Instructions: " + additionalInstructions + "\n\n" : ""
        );
        
        log.info("=== FIX TEST CASE PROMPT START ===");
        log.info("{}", prompt);
        log.info("=== FIX TEST CASE PROMPT END ===");
        
        String response = kiroCliCommonService.execute(prompt);
        String fixedSql = kiroCliCommonService.extractCodeFromSQLMarker(response, "SELECT");
        
        if (isSource) {
            tc.setSourceSql(fixedSql);
            tc.setSourceError(null);
            tc.setSourceStatus("FIXED");
        } else {
            tc.setTargetSql(fixedSql);
            tc.setTargetError(null);
            tc.setTargetStatus("FIXED");
        }
        
        testCaseRepository.save(tc);
        log.info("Fixed test case {} for {}", testCaseId, actualEngine);
        
        return "Test case fixed successfully. Please run the test again.";
    }
    
    public String fixTargetCodeWithGenAI(Long testCaseId, String additionalInstructions) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        // Get engine names from project
        var project = projectRepository.findById(obj.getProjectId()).orElse(null);
        String sourceEngine = "source database";
        String targetEngine = "target database";
        if (project != null) {
            if (project.getSourceEndpoint() != null && project.getSourceEndpoint().getDbEngine() != null) {
                sourceEngine = project.getSourceEndpoint().getDbEngine();
            }
            if (project.getTargetEndpoint() != null && project.getTargetEndpoint().getDbEngine() != null) {
                targetEngine = project.getTargetEndpoint().getDbEngine();
            }
        }
        
        // Get best available DDL
        String sourceDdl = getBestAvailableDdl(obj, false);
        String currentTargetDdl = getBestAvailableDdl(obj, true);
        
        String basePrompt = String.format(
            "Fix this %s function/procedure that is causing test failures.\n\n" +
            "Source %s DDL (Schema: %s):\n%s\n\n" +
            "Current Target %s DDL (Schema: %s):\n%s\n\n" +
            "Test Case SQL:\n%s\n\n" +
            "Error Message:\n%s\n\n" +
            "%s",
            targetEngine, 
            sourceEngine, obj.getSourceSchemaName(), sourceDdl, 
            targetEngine, obj.getTargetSchemaName(), currentTargetDdl, 
            tc.getTargetSql(), tc.getTargetError(),
            (additionalInstructions != null && !additionalInstructions.isEmpty()) 
                ? "Additional Instructions: " + additionalInstructions + "\n\n" : ""
        );
        
        String prompt = basePrompt + 
            String.format("Generate a corrected %s DDL (CREATE OR REPLACE) that fixes the error.\n", targetEngine) +
            "CRITICAL: Use the Target Schema (" + obj.getTargetSchemaName() + ") in the corrected DDL, NOT the Source Schema.\n" +
            "CRITICAL: Preserve ALL operators including * (multiplication), +, -, /, etc.\n" +
            "CRITICAL: For PostgreSQL procedures/functions, you MUST include the complete syntax:\n" +
            "  - Opening: AS $$ or AS $procedure$ or AS $function$\n" +
            "  - Body with BEGIN...END;\n" +
            "  - Closing: $$ or $procedure$ or $function$ (must match the opening delimiter)\n" +
            "  Example: CREATE OR REPLACE PROCEDURE my_proc() LANGUAGE plpgsql AS $$ BEGIN ... END; $$\n" +
            "  DO NOT forget the closing delimiter after END;\n" +
            "IMPORTANT: Return ONLY the corrected DDL wrapped in a code block.\n" +
            "Use triple backticks with sql language identifier:\n" +
            "```sql\n" +
            "your corrected ddl here\n" +
            "```";
        
        for (int attempt = 1; attempt <= baseService.maxRetries; attempt++) {
            log.info("=== FIX TARGET CODE PROMPT START (Attempt {}/{}) ===", attempt, baseService.maxRetries);
            log.info("{}", prompt);
            log.info("=== FIX TARGET CODE PROMPT END ===");
            
            String response = kiroCliCommonService.execute(prompt);
            String fixedDdl = kiroCliCommonService.extractCodeFromSQLMarker(response, "CREATE OR REPLACE", true);
            
            log.info("Deploying fixed DDL to {} (Attempt {}/{}): {}", targetEngine, attempt, baseService.maxRetries, fixedDdl);
            
            try {
                dbConnectionService.executePostgreSQLSql(obj.getTargetSchemaName(), fixedDdl);
                obj.setTargetDdl(fixedDdl);
                objectRepository.save(obj);
                
                // Refresh target DDL from database after successful deployment
                log.info("Refreshing target DDL from database for object {}", obj.getId());
                ddlService.retrieveTargetDdlFromDb(obj.getId());
                
                log.info("Successfully deployed fixed DDL for object {} on attempt {}", obj.getId(), attempt);
                return "Target code fixed and deployed successfully on attempt " + attempt + "!";
            } catch (Exception e) {
                String error = e.getMessage();
                log.error("Failed to deploy fixed DDL (Attempt {}/{}): {}", attempt, baseService.maxRetries, error);
                
                if (attempt < baseService.maxRetries) {
                    prompt = basePrompt + 
                        "Previous attempt generated this DDL:\n" + fixedDdl + "\n\n" +
                        "But it failed with error:\n" + error + "\n\n" +
                        String.format("Generate a corrected %s DDL that fixes this error.\n", targetEngine) +
                        "CRITICAL: Ensure ALL mathematical operators (* + - /) are preserved.\n" +
                        "IMPORTANT: Return ONLY the corrected DDL with NO additional text.\n" +
                        "Wrap your response with markers:\n" +
                        "<<<DDL_START>>>\n" +
                        "your corrected ddl here\n" +
                        "<<<DDL_END>>>";
                } else {
                    return "ERROR: Failed to deploy after " + baseService.maxRetries + " attempts. Last error: " + error;
                }
            }
        }
        
        return "ERROR: Max retries exceeded";
    }
    
    public List<TestCase> generateTestCases(Long objectId) throws Exception {
        log.info("Starting test case generation for object ID: {}", objectId);
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        log.info("Found object: {} {}.{}", obj.getSourceObjectType(), obj.getSourceSchemaName(), obj.getSourceObjectName());
        
        // Get source database engine
        var project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = project.getSourceEndpoint().getDbEngine().toUpperCase();
        
        // Get knowledge base content
        String kbContent = knowledgeBaseService.getKnowledgeBaseContent(project, "COMPARISON_TEST");
        if (!kbContent.isEmpty()) {
            kbContent = "Knowledge Base:\n" + kbContent + "\n\n";
        }
        
        // Get the best available source DDL
        String sourceDdl = getBestAvailableDdl(obj, false);
        
        List<TestCase> existingTests = testCaseRepository.findByObjectIdOrderByTestNumberAsc(objectId).stream()
            .filter(tc -> "COMPARISON".equals(tc.getTestType()))
            .toList();
        int startNumber = existingTests.isEmpty() ? 1 : 
            Integer.parseInt(existingTests.get(existingTests.size() - 1).getTestNumber()) + 1;
        
        String fullName = obj.getSourceSchemaName() + ".";
        if (obj.getSourcePackageName() != null && !obj.getSourcePackageName().isEmpty()) {
            fullName += obj.getSourcePackageName() + ".";
        }
        fullName += obj.getSourceObjectName();
        
        String prompt = String.format(
            "%s" +
            "Generate 3 test cases for %s %s %s:\n" +
            "1. Normal/happy path scenario\n" +
            "2. Edge case (null values, boundary conditions)\n" +
            "3. Complex scenario\n\n" +
            "For each test case provide:\n" +
            "- Test number (%d, %d, %d)\n" +
            "- Description\n" +
            "- Scenario type\n" +
            "- SQL statement to execute (use full qualified name: %s)\n\n" +
            "DDL:\n%s\n\n" +
            "IMPORTANT: Return ONLY valid JSON array with NO additional text.\n" +
            "Format: [{\"test_number\":\"%d\",\"description\":\"...\",\"scenario\":\"...\",\"sql\":\"...\"}]\n" +
            "Wrap your response with markers:\n" +
            "<<<JSON_START>>>\n" +
            "[your json here]\n" +
            "<<<JSON_END>>>",
            kbContent,
            sourceEngine, obj.getSourceObjectType(), fullName, startNumber, startNumber + 1, startNumber + 2, 
            fullName, sourceDdl, startNumber
        );
        
        log.info("Executing Kiro with prompt length: {}", prompt.length());
        String fullOutput = kiroCliCommonService.execute(prompt);
        String response = kiroCliCommonService.extractJSONArrayFromJSONMarker(fullOutput);
        log.info("Kiro response length: {}", response.length());
        log.debug("Kiro response: {}", response);
        
        List<TestCase> testCases = baseService.parseTestCases(response, objectId);
        log.info("Parsed {} test cases", testCases.size());
        
        for (TestCase tc : testCases) {
            tc.setTestType("COMPARISON");
            tc.setCreatedAt(LocalDateTime.now());
            tc.setSourceStatus("CREATED");
            testCaseRepository.save(tc);
            log.info("Saved test case: {}", tc.getTestNumber());
        }
        
        return testCases;
    }
    
    public void executeSourceTest(Long testCaseId) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        // Get source engine from project
        var project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = project.getSourceEndpoint().getDbEngine();
        
        log.info("Executing {} test case {} for {}.{}", sourceEngine, tc.getTestNumber(), obj.getSourceSchemaName(), obj.getSourceObjectName());
        
        Map<String, Object> result;
        if ("oracle".equalsIgnoreCase(sourceEngine)) {
            result = dbConnectionService.executeOracleTestWithTestUser(obj.getSourceSchemaName(), tc.getSourceSql());
        } else if ("sqlserver".equalsIgnoreCase(sourceEngine)) {
            result = dbConnectionService.executeSqlServerTestWithTestUser(obj.getSourceSchemaName(), tc.getSourceSql());
        } else {
            throw new UnsupportedOperationException("Source engine not supported: " + sourceEngine);
        }
        
        tc.setSourceStatus((String) result.get("status"));
        tc.setSourceExecutionTimeMs((Long) result.get("execution_time_ms"));
        tc.setSourceResult((String) result.get("query_output"));
        tc.setSourceError((String) result.get("error_message"));
        tc.setExpectedResult(tc.getSourceResult());
        tc.setSourceExecutedAt(LocalDateTime.now());
        
        testCaseRepository.save(tc);
        
        // Note: Auto-regeneration disabled - edge cases may intentionally fail
        // Users can manually use "Fix Test Case" button if needed
        
        baseService.updateObjectStatus(tc.getObjectId());
    }
    
    public void convertToPostgreSQL(Long testCaseId) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        // Get engine names from project
        var project = projectRepository.findById(obj.getProjectId()).orElse(null);
        String sourceEngine = "source database";
        String targetEngine = "target database";
        if (project != null) {
            if (project.getSourceEndpoint() != null && project.getSourceEndpoint().getDbEngine() != null) {
                sourceEngine = project.getSourceEndpoint().getDbEngine();
            }
            if (project.getTargetEndpoint() != null && project.getTargetEndpoint().getDbEngine() != null) {
                targetEngine = project.getTargetEndpoint().getDbEngine();
            }
        }
        
        String prompt = String.format(
            "Convert this %s SQL to %s syntax:\n" +
            "%s SQL: %s\n\n" +
            "Source schema: %s\n" +
            "Target schema: %s\n" +
            "Target function: %s\n\n" +
            "Adjust:\n" +
            "- Use Target Schema (%s) instead of Source Schema\n" +
            "- Function names to lowercase\n" +
            "- Adapt %s-specific syntax to %s\n\n" +
            "IMPORTANT: Return ONLY the %s SQL statement with NO additional text.\n" +
            "Wrap your response with markers:\n" +
            "<<<SQL_START>>>\n" +
            "your sql here\n" +
            "<<<SQL_END>>>",
            sourceEngine, targetEngine,
            sourceEngine, tc.getSourceSql(),
            obj.getSourceSchemaName(),
            obj.getTargetSchemaName(),
            obj.getTargetObjectName(),
            obj.getTargetSchemaName(),
            sourceEngine, targetEngine,
            targetEngine
        );
        
        log.info("=== DDL CONVERSION PROMPT START ===");
        log.info("{}", prompt);
        log.info("=== DDL CONVERSION PROMPT END ===");
        
        String response = kiroCliCommonService.execute(prompt);
        
        // Remove ANSI escape codes
        String cleaned = response.replaceAll("\u001B\\[[;\\d]*[mGKHf]", "")
                                 .replaceAll("\\[\\d*[mGKHf]", "");
        
        int startMarker = cleaned.indexOf("<<<SQL_START>>>");
        int endMarker = cleaned.indexOf("<<<SQL_END>>>");
        
        String pgSql;
        if (startMarker != -1 && endMarker != -1) {
            pgSql = cleaned.substring(startMarker + 15, endMarker).trim();
        } else {
            pgSql = cleaned.trim();
        }
        
        tc.setTargetSql(pgSql);
        tc.setTargetStatus("CONVERTED");
        testCaseRepository.save(tc);
    }
    
    public void executePostgreSQLTest(Long testCaseId) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        log.info("Executing PostgreSQL test case {} for {}.{}", tc.getTestNumber(), obj.getTargetSchemaName(), obj.getTargetObjectName());
        
        Map<String, Object> result = dbConnectionService.executePostgreSQLTestWithTestUser(obj.getTargetSchemaName(), tc.getTargetSql());
        
        tc.setTargetStatus((String) result.get("status"));
        tc.setTargetExecutionTimeMs((Long) result.get("execution_time_ms"));
        tc.setTargetResult((String) result.get("query_output"));
        tc.setTargetError((String) result.get("error_message"));
        tc.setTargetExecutedAt(LocalDateTime.now());
        
        compareResults(tc);
        testCaseRepository.save(tc);
        
        baseService.updateObjectStatus(tc.getObjectId());
        
        // Throw exception if test failed
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
    }
    
    private void compareResults(TestCase tc) {
        if (tc.getSourceResult() == null || tc.getTargetResult() == null) {
            tc.setValidationResult("INCOMPLETE");
            tc.setResultsIdentical(false);
            return;
        }
        
        String sourceValue = extractResultValue(tc.getSourceResult());
        String targetValue = extractResultValue(tc.getTargetResult());
        
        boolean identical = sourceValue.equals(targetValue);
        tc.setResultsIdentical(identical);
        tc.setValidationResult(identical ? "PASS" : "FAIL");
        
        if (!identical) {
            tc.setValidationNotes("Results differ.\nSource: " + sourceValue + "\nTarget: " + targetValue);
        } else {
            tc.setValidationNotes(null);
        }
        
        if (tc.getSourceExecutionTimeMs() != null && tc.getTargetExecutionTimeMs() != null) {
            double ratio = (double) tc.getTargetExecutionTimeMs() / tc.getSourceExecutionTimeMs();
            String perfNote = String.format("Execution time ratio: %.2fx", ratio);
            tc.setValidationNotes((tc.getValidationNotes() != null ? tc.getValidationNotes() + "\n" : "") + perfNote);
        }
    }
    
    private String extractResultValue(String output) {
        if (output == null) return "";
        
        // Extract value after last meaningful "Result: " line (skip ROLLBACK/COMMIT)
        String[] lines = output.split("\n");
        String lastResult = null;
        String currentStatement = null;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Track current statement being processed
            if (trimmed.startsWith("=== Statement")) {
                currentStatement = null;
            } else if (currentStatement == null && !trimmed.isEmpty() && 
                       !trimmed.startsWith("Status:") && !trimmed.startsWith("Result:") && 
                       !trimmed.startsWith("Time:") && !trimmed.startsWith("Notices:")) {
                currentStatement = trimmed.toUpperCase();
            }
            
            // Capture result, but skip if it's from ROLLBACK or COMMIT
            if (trimmed.startsWith("Result:")) {
                if (currentStatement == null || 
                    (!currentStatement.startsWith("ROLLBACK") && !currentStatement.startsWith("COMMIT"))) {
                    lastResult = line.substring(line.indexOf("Result:") + 7).trim();
                }
            }
        }
        
        return lastResult != null ? lastResult : output.trim();
    }
    
    private void regenerateTestCase(Long testCaseId) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        // Get source engine from project
        var project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = project.getSourceEndpoint().getDbEngine().toUpperCase();
        
        // Get best available source DDL
        String sourceDdl = getBestAvailableDdl(obj, false);
        
        String fullName = obj.getSourceSchemaName() + ".";
        if (obj.getSourcePackageName() != null && !obj.getSourcePackageName().isEmpty()) {
            fullName += obj.getSourcePackageName() + ".";
        }
        fullName += obj.getSourceObjectName();
        
        String prompt = String.format(
            "Previous test case failed with error: %s\n\n" +
            "Generate a corrected test case for %s %s %s\n" +
            "Scenario: %s\n\n" +
            "IMPORTANT: Use full qualified name: %s\n\n" +
            "DDL:\n%s\n\n" +
            "Return ONLY the SQL statement, nothing else.",
            tc.getSourceError(), 
            sourceEngine, obj.getSourceObjectType(), fullName, 
            tc.getScenario(), fullName, sourceDdl
        );
        
        String response = kiroCliCommonService.execute(prompt);
        
        String newSql = null;
        String[] lines = response.split("\n");
        StringBuilder sqlBlock = new StringBuilder();
        boolean inSqlBlock = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            if (!inSqlBlock && (trimmed.isEmpty() || trimmed.equals("sql") || trimmed.equals(">"))) {
                continue;
            }
            
            if (!inSqlBlock && (trimmed.toUpperCase().startsWith("SELECT") || 
                                trimmed.toUpperCase().startsWith("DECLARE") ||
                                trimmed.toUpperCase().startsWith("BEGIN"))) {
                inSqlBlock = true;
                sqlBlock.append(line).append("\n");
                
                if (trimmed.toUpperCase().startsWith("SELECT") && trimmed.contains(";")) {
                    newSql = trimmed;
                    break;
                }
                continue;
            }
            
            if (inSqlBlock) {
                sqlBlock.append(line).append("\n");
                if (trimmed.toUpperCase().equals("END;") || trimmed.toUpperCase().startsWith("END;")) {
                    newSql = sqlBlock.toString().trim();
                    break;
                }
            }
        }
        
        if (newSql == null) {
            newSql = sqlBlock.toString().trim();
            if (newSql.isEmpty()) {
                newSql = response.trim();
            }
        }
        
        log.info("Extracted SQL: {}", newSql);
        
        tc.setSourceSql(newSql);
        tc.setSourceError(null);
        testCaseRepository.save(tc);
    }
    
    /**
     * Get the best available DDL for an object.
     * Priority: UserOverwrite > DdlFromDb > try retrieve from DB > S3 DDL
     */
    private String getBestAvailableDdl(DatabaseObject obj, boolean isTarget) {
        String userOverwrite = isTarget ? obj.getTargetDdlUserOverwrite() : obj.getSourceDdlUserOverwrite();
        String ddlFromDb = isTarget ? obj.getTargetDdlFromDb() : obj.getSourceDdlFromDb();
        String s3Ddl = isTarget ? obj.getTargetDdl() : obj.getSourceDdl();
        
        // Priority 1: User overwrite
        if (userOverwrite != null && !userOverwrite.trim().isEmpty()) {
            log.info("Using {} DDL from user overwrite", isTarget ? "target" : "source");
            return userOverwrite;
        }
        
        // Priority 2: DDL from DB
        if (ddlFromDb != null && !ddlFromDb.trim().isEmpty()) {
            log.info("Using {} DDL from database", isTarget ? "target" : "source");
            return ddlFromDb;
        }
        
        // Priority 3: Try to retrieve from DB
        try {
            log.info("Attempting to retrieve {} DDL from database", isTarget ? "target" : "source");
            if (isTarget) {
                ddlService.retrieveTargetDdlFromDb(obj.getId());
            } else {
                ddlService.retrieveSourceDdlFromDb(obj.getId());
            }
            // Refresh object to get updated DDL
            obj = objectRepository.findById(obj.getId()).orElse(obj);
            ddlFromDb = isTarget ? obj.getTargetDdlFromDb() : obj.getSourceDdlFromDb();
            if (ddlFromDb != null && !ddlFromDb.trim().isEmpty()) {
                log.info("Successfully retrieved {} DDL from database", isTarget ? "target" : "source");
                return ddlFromDb;
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve {} DDL from database: {}", isTarget ? "target" : "source", e.getMessage());
        }
        
        // Priority 4: Fallback to S3 DDL
        log.info("Using {} DDL from S3", isTarget ? "target" : "source");
        return s3Ddl != null ? s3Ddl : "";
    }
}
