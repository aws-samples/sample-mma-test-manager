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
 * Service for PostgreSQL unit testing.
 * Tests target database objects independently without comparison to source.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnitTestService {
    private final TestCaseRepository testCaseRepository;
    private final DatabaseObjectRepository objectRepository;
    private final DatabaseConnectionService dbConnectionService;
    private final TestCaseBaseService baseService;
    private final KiroCliCommonService kiroCliCommonService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final com.mma.testmanager.repository.ProjectRepository projectRepository;
    private final DdlService ddlService;
    
    public void generateUnitTests(Long objectId) throws Exception {
        log.info("Starting unit test generation for object ID: {}", objectId);
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        
        List<TestCase> existingTests = testCaseRepository.findByObjectIdOrderByTestNumberAsc(objectId).stream()
            .filter(tc -> "UNIT".equals(tc.getTestType()))
            .toList();
        int startNumber = existingTests.isEmpty() ? 1 : 
            Integer.parseInt(existingTests.get(existingTests.size() - 1).getTestNumber()) + 1;
        
        String fullName = obj.getTargetSchemaName() + "." + obj.getTargetObjectName();
        
        // Get the best available DDL (prefer from DB, fallback to S3)
        String targetDdl = getBestAvailableDdl(obj, true);
        
        // Get knowledge base content and engine names from project
        var project = projectRepository.findById(obj.getProjectId()).orElse(null);
        String kbContent = "";
        String targetEngine = "target database";
        if (project != null) {
            kbContent = knowledgeBaseService.getKnowledgeBaseContent(project, "UNIT_TEST");
            if (!kbContent.isEmpty()) {
                kbContent = "Knowledge Base:\n" + kbContent + "\n\n";
            }
            if (project.getTargetEndpoint() != null && project.getTargetEndpoint().getDbEngine() != null) {
                targetEngine = project.getTargetEndpoint().getDbEngine();
            }
        }
        
        String prompt = String.format(
            "%s" +
            "Generate 3 %s unit test cases for %s %s:\n" +
            "1. Normal/happy path scenario\n" +
            "2. Edge case (null values, boundary conditions)\n" +
            "3. Error handling scenario\n\n" +
            "For each test case provide:\n" +
            "- Test number (%d, %d, %d)\n" +
            "- Description (max 200 characters)\n" +
            "- Scenario type (max 200 characters)\n" +
            "- SQL statement to execute (use full qualified name: %s)\n\n" +
            "DDL:\n%s\n\n" +
            "IMPORTANT: Keep description and scenario concise and under 200 characters each.\n" +
            "Return ONLY valid JSON array with NO additional text.\n" +
            "Format: [{\"test_number\":\"%d\",\"description\":\"...\",\"scenario\":\"...\",\"sql\":\"...\"}]\n" +
            "Wrap your response with markers:\n" +
            "<<<JSON_START>>>\n" +
            "[your json here]\n" +
            "<<<JSON_END>>>",
            kbContent,
            targetEngine,
            obj.getSourceObjectType(), fullName, startNumber, startNumber + 1, startNumber + 2, 
            fullName, targetDdl, startNumber
        );
        
        String fullOutput = kiroCliCommonService.execute(prompt);
        String response = kiroCliCommonService.extractJSONArrayFromJSONMarker(fullOutput);
        List<TestCase> testCases = baseService.parseTestCases(response, objectId);
        
        for (TestCase tc : testCases) {
            tc.setTestType("UNIT");
            tc.setTargetSql(tc.getSourceSql());
            tc.setSourceSql(null);
            tc.setTargetStatus("CREATED");
            tc.setCreatedAt(LocalDateTime.now());
            testCaseRepository.save(tc);
        }
    }
    
    public void updateUnitTest(Long testCaseId, String sql) {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        tc.setTargetSql(sql);
        testCaseRepository.save(tc);
    }
    
    public void executePostgreSQLTest(Long testCaseId) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        log.info("Executing unit test {} for {}.{}", tc.getTestNumber(), obj.getTargetSchemaName(), obj.getTargetObjectName());
        
        Map<String, Object> result = dbConnectionService.executePostgreSQLTestWithTestUser(obj.getTargetSchemaName(), tc.getTargetSql());
        
        tc.setTargetStatus((String) result.get("status"));
        tc.setTargetExecutionTimeMs((Long) result.get("execution_time_ms"));
        tc.setTargetResult((String) result.get("query_output"));
        tc.setTargetError((String) result.get("error_message"));
        tc.setTargetExecutedAt(LocalDateTime.now());
        
        testCaseRepository.save(tc);
        
        baseService.updateObjectStatus(tc.getObjectId());
        
        // Throw exception if test failed
        if ("FAILURE".equals(result.get("status"))) {
            throw new RuntimeException((String) result.get("error_message"));
        }
    }
    
    public String fixUnitTestCase(Long testCaseId, String additionalInstructions) throws Exception {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        DatabaseObject obj = objectRepository.findById(tc.getObjectId()).orElseThrow();
        
        // Get target engine name from project
        var project = projectRepository.findById(obj.getProjectId()).orElse(null);
        String targetEngine = "target database";
        if (project != null && project.getTargetEndpoint() != null && project.getTargetEndpoint().getDbEngine() != null) {
            targetEngine = project.getTargetEndpoint().getDbEngine();
        }
        
        // Get knowledge base content
        String kbContent = "";
        if (project != null) {
            kbContent = knowledgeBaseService.getKnowledgeBaseContent(project, "UNIT_TEST");
            if (!kbContent.isEmpty()) {
                kbContent = "Knowledge Base:\n" + kbContent + "\n\n";
            }
        }
        
        String currentTargetDdl = getBestAvailableDdl(obj, true);
        
        String prompt = String.format(
            "%s" +
            "Fix this %s unit test case that failed with an error.\n\n" +
            "Object: %s %s.%s\n" +
            "Test Description: %s\n" +
            "Test Scenario: %s\n\n" +
            "Current SQL:\n%s\n\n" +
            "Error Message:\n%s\n\n" +
            "DDL:\n%s\n\n" +
            "%s" +
            "Generate a corrected SQL statement that fixes the error.\n\n" +
            "CRITICAL: You MUST wrap your final SQL answer in a code block with ```sql marker.\n" +
            "Do NOT include any explanatory text after the code block.\n" +
            "Format:\n" +
            "```sql\n" +
            "your sql statements here\n" +
            "```",
            kbContent,
            targetEngine,
            obj.getSourceObjectType(), obj.getTargetSchemaName(), obj.getTargetObjectName(), tc.getDescription(), tc.getScenario(),
            tc.getTargetSql(), tc.getTargetError(), currentTargetDdl,
            (additionalInstructions != null && !additionalInstructions.isEmpty()) 
                ? "Additional Instructions: " + additionalInstructions + "\n\n" : ""
        );
        
        log.info("=== FIX UNIT TEST CASE PROMPT START ===");
        log.info("{}", prompt);
        log.info("=== FIX UNIT TEST CASE PROMPT END ===");
        
        String response = kiroCliCommonService.execute(prompt);
        String fixedSql = kiroCliCommonService.extractCodeFromSQLMarker(response, "SELECT");
        
        tc.setTargetSql(fixedSql);
        tc.setTargetError(null);
        tc.setTargetStatus("FIXED");
        testCaseRepository.save(tc);
        
        return "Test case fixed successfully. Please run the test again.";
    }
    
    public String fixUnitTargetCode(Long testCaseId, String additionalInstructions) throws Exception {
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
            "Fix this %s function/procedure that is causing unit test failures.\n\n" +
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
            "Generate a corrected %s DDL (CREATE OR REPLACE) that fixes the error.\n" +
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
            log.info("=== FIX UNIT TARGET CODE PROMPT START (Attempt {}/{}) ===", attempt, baseService.maxRetries);
            log.info("{}", prompt);
            log.info("=== FIX UNIT TARGET CODE PROMPT END ===");
            
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
                        "IMPORTANT: Return ONLY the corrected DDL wrapped in a code block.\n" +
                        "Use triple backticks with sql language identifier:\n" +
                        "```sql\n" +
                        "your corrected ddl here\n" +
                        "```";
                } else {
                    return "ERROR: Failed to deploy after " + baseService.maxRetries + " attempts. Last error: " + error;
                }
            }
        }
        
        return "ERROR: Max retries exceeded";
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
