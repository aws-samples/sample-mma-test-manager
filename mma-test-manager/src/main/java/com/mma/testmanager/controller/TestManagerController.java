package com.mma.testmanager.controller;

import com.mma.testmanager.entity.DatabaseEndpoint;
import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.Project;
import com.mma.testmanager.entity.TestCase;
import com.mma.testmanager.repository.DatabaseEndpointRepository;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import com.mma.testmanager.repository.ProjectRepository;
import com.mma.testmanager.service.DdlService;
import com.mma.testmanager.service.S3LoaderService;
import com.mma.testmanager.service.OracleCommonService;
import com.mma.testmanager.service.SQLServerCommonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class TestManagerController {
    private final S3LoaderService s3LoaderService;
    private final com.mma.testmanager.service.TestCaseBaseService testCaseBaseService;
    private final com.mma.testmanager.service.ComparisonTestService comparisonTestService;
    private final com.mma.testmanager.service.UnitTestService unitTestService;
    private final DatabaseObjectRepository repository;
    private final ProjectRepository projectRepository;
    private final DatabaseEndpointRepository endpointRepository;
    private final DdlService ddlService;
    private final com.mma.testmanager.service.DependencyService dependencyService;
    private final OracleCommonService oracleService;
    private final SQLServerCommonService sqlServerService;
    private final com.mma.testmanager.repository.ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository;
    private final com.mma.testmanager.repository.KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.mma.testmanager.repository.TestCaseRepository testCaseRepository;
    private final BuildProperties buildProperties;
    
    @org.springframework.beans.factory.annotation.Value("${mma.s3.default-path:}")
    private String defaultS3Path;
    
    @org.springframework.beans.factory.annotation.Value("${mma.sourcedb.connection.secret:}")
    private String sourceDbConnectionSecret;
    
    @org.springframework.beans.factory.annotation.Value("${mma.targetdb.connection.secret:}")
    private String targetDbConnectionSecret;
    
    @org.springframework.beans.factory.annotation.Value("${mma.page-size:50}")
    private int pageSize;
    
    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("version", buildProperties.getVersion());
        return "login";
    }
    
    @GetMapping("/")
    public String index(Model model) {
        List<Project> projects = projectRepository.findAllByOrderByCreatedAtDesc();
        model.addAttribute("projects", projects);
        model.addAttribute("defaultS3Path", defaultS3Path);
        
        // Add database connection info
        addDatabaseConnectionInfo(model, "source", sourceDbConnectionSecret);
        addDatabaseConnectionInfo(model, "target", targetDbConnectionSecret);
        
        return "index";
    }
    
    private void addDatabaseConnectionInfo(Model model, String dbRole, String secretArn) {
        if (secretArn == null || secretArn.isEmpty()) {
            return;
        }
        
        try (software.amazon.awssdk.services.secretsmanager.SecretsManagerClient client = 
                software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.builder().build()) {
            
            String secretString = client.getSecretValue(r -> r.secretId(secretArn)).secretString();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(secretString);
            
            String host = json.path("host").asText();
            String port = json.path("port").asText();
            String dbname = json.path("dbname").asText();
            String username = json.path("username").asText();
            String engine = json.path("engine").asText();
            
            String connectionString;
            String iconPath;
            String engineName;
            
            if ("oracle".equalsIgnoreCase(engine)) {
                connectionString = String.format("sqlplus %s@%s:%s/%s", username, host, port, dbname);
                iconPath = "/images/oracle.png";
                engineName = "Oracle";
            } else if ("postgres".equalsIgnoreCase(engine) || "postgresql".equalsIgnoreCase(engine)) {
                connectionString = String.format("psql -h %s -p %s -d %s -U %s", host, port, dbname, username);
                iconPath = "/images/postgres.png";
                engineName = "PostgreSQL";
            } else if ("sqlserver".equalsIgnoreCase(engine)) {
                connectionString = String.format("sqlcmd -S %s,%s -d %s -U %s -C", host, port, dbname, username);
                iconPath = "/images/sqlserver.png";
                engineName = "SQL Server";
            } else if ("mysql".equalsIgnoreCase(engine)) {
                connectionString = String.format("mysql -h %s -P %s -D %s -u %s", host, port, dbname, username);
                iconPath = "/images/mysql.png";
                engineName = "MySQL";
            } else {
                connectionString = String.format("%s://%s:%s/%s (user: %s)", engine, host, port, dbname, username);
                iconPath = "/images/convert.png";
                engineName = engine.toUpperCase();
            }
            
            model.addAttribute(dbRole + "DbIcon", iconPath);
            model.addAttribute(dbRole + "DbEngine", engineName);
            model.addAttribute(dbRole + "DbConnectionString", connectionString);
        } catch (Exception e) {
            log.warn("Failed to load {} database connection info", dbRole, e);
        }
    }
    
    @PostMapping("/project/{projectId}/delete")
    @ResponseBody
    @Transactional
    public String deleteProject(@PathVariable String projectId) {
        try {
            log.warn("Deleting project: {}", projectId);
            testCaseBaseService.deleteByProjectId(projectId);
            repository.deleteByProjectId(projectId);
            projectRepository.deleteById(projectId);
            log.warn("Successfully deleted project: {}", projectId);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Failed to delete project {}: {}", projectId, e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @GetMapping("/endpoint/{endpointId}")
    @ResponseBody
    public DatabaseEndpoint getEndpoint(@PathVariable Long endpointId) {
        return endpointRepository.findById(endpointId).orElse(null);
    }
    
    @PostMapping("/load")
    public String loadProject(@RequestParam String s3Path, Model model) {
        s3LoaderService.startLoad(s3Path);
        return "redirect:/";
    }
    
    @GetMapping("/loading/{projectId}")
    public String loadingPage(@PathVariable String projectId, Model model) {
        model.addAttribute("projectId", projectId);
        return "loading";
    }
    
    @GetMapping("/progress/{projectId}")
    @ResponseBody
    public String getProgress(@PathVariable String projectId) {
        return s3LoaderService.getProgress(projectId);
    }
    
    @GetMapping("/project/{projectId}")
    public String viewProject(@PathVariable String projectId, 
                             @RequestParam(defaultValue = "code") String tab,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(required = false) Boolean showComplete,
                             @RequestParam(required = false) Boolean showIgnored,
                             @RequestParam(required = false) String search,
                             Model model) {
        // Default to true if not specified
        boolean filterComplete = showComplete == null || showComplete;
        boolean filterIgnored = showIgnored == null || showIgnored;
        
        // Get source database engine to determine code object types
        Project project = projectRepository.findById(projectId).orElse(null);
        String sourceDbEngine = project != null && project.getSourceEndpoint() != null 
            ? project.getSourceEndpoint().getDbEngine() : "unknown";
        String targetDbEngine = project != null && project.getTargetEndpoint() != null
            ? project.getTargetEndpoint().getDbEngine() : "unknown";
        
        String sourceEngine = getEngineName(sourceDbEngine);
        String targetEngine = getEngineName(targetDbEngine);
        
        List<String> objectTypes;
        List<String> excludeTypes;
        if ("code".equals(tab)) {
            // Code tab: include code object types based on source database engine
            List<String> codeTypes = new ArrayList<>();
            codeTypes.add("PROCEDURE");
            codeTypes.add("FUNCTION");
            codeTypes.add("TRIGGER");
            codeTypes.add("VIEW");
            
            if ("sqlserver".equalsIgnoreCase(sourceDbEngine)) {
                codeTypes.add("SQL_STORED_PROCEDURE");
                codeTypes.add("SQL_SCALAR_FUNCTION");
                codeTypes.add("SQL_INLINE_FUNCTION");
                codeTypes.add("SQL_TABLE_VALUED_FUNCTION");
            }
            
            objectTypes = codeTypes;
            excludeTypes = null;
        } else {
            // Other tab: exclude code objects and metadata/folder types
            objectTypes = null;
            
            // Build exclude list dynamically
            List<String> excludeList = new ArrayList<>();
            
            // Add code object types
            excludeList.addAll(List.of("PROCEDURE", "FUNCTION", "TRIGGER", "VIEW"));
            
            // Add SQL Server code types
            if ("sqlserver".equalsIgnoreCase(sourceDbEngine)) {
                excludeList.addAll(List.of(
                    "SQL_STORED_PROCEDURE", "SQL_SCALAR_FUNCTION", 
                    "SQL_INLINE_FUNCTION", "SQL_TABLE_VALUED_FUNCTION"
                ));
            }
            
            // Add metadata/folder types based on source engine
            if ("oracle".equalsIgnoreCase(sourceDbEngine)) {
                excludeList.addAll(oracleService.getOracleMetadataTypes());
            } else if ("sqlserver".equalsIgnoreCase(sourceDbEngine)) {
                excludeList.addAll(sqlServerService.getSQLServerMetadataTypes());
            }
            
            excludeTypes = excludeList;
        }
        
        org.springframework.data.domain.Pageable pageable = 
            org.springframework.data.domain.PageRequest.of(page, pageSize, 
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.asc("sourceSchemaName"),
                    org.springframework.data.domain.Sort.Order.asc("sourceObjectType"),
                    org.springframework.data.domain.Sort.Order.asc("sourcePackageName"),
                    org.springframework.data.domain.Sort.Order.asc("sourceObjectName")
                ));
        
        org.springframework.data.domain.Page<DatabaseObject> objectsPage;
        if (search != null && !search.trim().isEmpty()) {
            String includeTypesStr = objectTypes == null ? "" : String.join(",", objectTypes);
            String excludeTypesStr = excludeTypes == null ? "" : String.join(",", excludeTypes);
            // For native query, use unsorted pageable since ORDER BY is in the SQL
            org.springframework.data.domain.Pageable unsortedPageable = 
                org.springframework.data.domain.PageRequest.of(page, pageSize);
            objectsPage = repository.findByProjectIdWithFiltersAndSearch(
                projectId, includeTypesStr, excludeTypesStr, filterComplete, filterIgnored, search.trim(), unsortedPageable);
        } else {
            objectsPage = repository.findByProjectIdWithFilters(
                projectId, objectTypes, excludeTypes, filterComplete, filterIgnored, pageable);
        }
        
        model.addAttribute("projectId", projectId);
        model.addAttribute("search", search);
        model.addAttribute("objects", objectsPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", objectsPage.getTotalPages());
        model.addAttribute("showComplete", filterComplete);
        model.addAttribute("showIgnored", filterIgnored);
        model.addAttribute("tab", tab);
        model.addAttribute("project", project);
        
        model.addAttribute("sourceEngine", sourceEngine);
        model.addAttribute("targetEngine", targetEngine);
        model.addAttribute("sourceSupportsPackages", "oracle".equalsIgnoreCase(sourceDbEngine));
        model.addAttribute("targetSupportsPackages", "oracle".equalsIgnoreCase(targetDbEngine));
        
        return "project";
    }
    
    @GetMapping("/comparison/{objectId}")
    public String comparisonPage(@PathVariable Long objectId, Model model) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        List<TestCase> testCases = testCaseBaseService.findByObjectIdAndType(objectId, "COMPARISON");
        
        // Get source and target database engines
        var project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngineRaw = project.getSourceEndpoint().getDbEngine();
        String targetEngineRaw = project.getTargetEndpoint().getDbEngine();
        String sourceEngine = getEngineName(sourceEngineRaw);
        String targetEngine = getEngineName(targetEngineRaw);
        String sourceIcon = getEngineIcon(sourceEngineRaw);
        String targetIcon = getEngineIcon(targetEngineRaw);
        
        model.addAttribute("object", obj);
        model.addAttribute("testCases", testCases);
        model.addAttribute("sourceEngine", sourceEngine);
        model.addAttribute("targetEngine", targetEngine);
        model.addAttribute("sourceEngineRaw", sourceEngineRaw);
        model.addAttribute("targetEngineRaw", targetEngineRaw);
        model.addAttribute("sourceIcon", sourceIcon);
        model.addAttribute("targetIcon", targetIcon);
        return "comparison";
    }
    
    @GetMapping("/unit/{objectId}")
    public String unitPage(@PathVariable Long objectId, Model model) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        List<TestCase> testCases = testCaseBaseService.findByObjectIdAndType(objectId, "UNIT");
        Project project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        
        String targetDbEngine = project.getTargetDbEngine();
        String targetEngine = getEngineName(targetDbEngine);
        
        model.addAttribute("object", obj);
        model.addAttribute("testCases", testCases);
        model.addAttribute("targetEngine", targetEngine);
        return "unit";
    }
    
    @PostMapping("/unit/{objectId}/generate")
    @ResponseBody
    public String generateUnitTests(@PathVariable Long objectId) {
        try {
            unitTestService.generateUnitTests(objectId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/unit/{testCaseId}/execute")
    @ResponseBody
    public String executeUnitTest(@PathVariable Long testCaseId) {
        try {
            unitTestService.executePostgreSQLTest(testCaseId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/unit/{testCaseId}/update")
    @ResponseBody
    public String updateUnitTest(@PathVariable Long testCaseId, @RequestParam String sql) {
        try {
            unitTestService.updateUnitTest(testCaseId, sql);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/unit/{testCaseId}/delete")
    @ResponseBody
    public String deleteUnitTest(@PathVariable Long testCaseId) {
        try {
            testCaseBaseService.deleteTestCase(testCaseId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/unit/{testCaseId}/fix-test")
    @ResponseBody
    public String fixUnitTestCase(@PathVariable Long testCaseId, 
                                    @RequestParam(required = false, defaultValue = "") String additionalInstructions) {
        try {
            return unitTestService.fixUnitTestCase(testCaseId, additionalInstructions);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/unit/{testCaseId}/fix-target")
    @ResponseBody
    public String fixUnitTargetCode(@PathVariable Long testCaseId,
                                      @RequestParam(required = false, defaultValue = "") String additionalInstructions) {
        try {
            return unitTestService.fixUnitTargetCode(testCaseId, additionalInstructions);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/description")
    @ResponseBody
    public String updateDescription(@PathVariable Long testCaseId, @RequestParam String description) {
        TestCase tc = testCaseRepository.findById(testCaseId).orElseThrow();
        tc.setDescription(description);
        testCaseRepository.save(tc);
        return "OK";
    }
    
    @GetMapping("/ddl/{objectId}")
    public String ddlPage(@PathVariable Long objectId, Model model) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        
        // Get source and target database engines
        var project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = getEngineName(project.getSourceEndpoint().getDbEngine());
        String targetEngine = getEngineName(project.getTargetEndpoint().getDbEngine());
        
        // Auto-retrieve target DDL from database if S3 DDL is empty (only for supported types)
        if ((obj.getTargetDdl() == null || obj.getTargetDdl().trim().isEmpty()) 
            && obj.getTargetDdlFromDb() == null
            && isSupportedForDdlRetrieval(obj.getSourceObjectType())) {
            try {
                ddlService.retrieveTargetDdlFromDb(objectId);
                obj = repository.findById(objectId).orElseThrow(); // Reload
            } catch (Exception e) {
                log.warn("Could not auto-retrieve target DDL for object {}: {}", objectId, e.getMessage());
            }
        }
        
        model.addAttribute("object", obj);
        model.addAttribute("sourceEngine", sourceEngine);
        model.addAttribute("targetEngine", targetEngine);
        return "ddl";
    }
    
    @GetMapping("/dependencies/{objectId}")
    public String dependenciesPage(@PathVariable Long objectId, Model model) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        model.addAttribute("object", obj);
        return "dependencies";
    }
    
    @PostMapping("/dependencies/{objectId}/analyze-source")
    @ResponseBody
    public Map<String, Object> analyzeSourceDependencies(@PathVariable Long objectId) {
        try {
            var dependencies = dependencyService.getObjectDependencies(objectId);
            var tableDeps = dependencies.stream()
                .filter(d -> "TABLE".equals(d.getReferencedType()) || "USER_TABLE".equals(d.getReferencedType()))
                .distinct()
                .toList();
            
            // Get target table names for backup scripts
            var targetTableDeps = tableDeps.stream()
                .map(dep -> {
                    // Find corresponding target table
                    var targetObj = repository.findByProjectIdAndSourceSchemaNameAndSourceObjectName(
                        repository.findById(objectId).orElseThrow().getProjectId(),
                        dep.getReferencedOwner(),
                        dep.getReferencedName()
                    ).stream().findFirst();
                    
                    if (targetObj.isPresent()) {
                        var target = new com.mma.testmanager.entity.Dependency();
                        target.setReferencedOwner(targetObj.get().getTargetSchemaName());
                        target.setReferencedName(targetObj.get().getTargetObjectName());
                        target.setBackupScript(String.format(
                            "CREATE TABLE %s.%s_' || TO_CHAR(CURRENT_TIMESTAMP, 'YYMMDDHH24MI') || ' AS SELECT * FROM %s.%s;",
                            targetObj.get().getTargetSchemaName(),
                            targetObj.get().getTargetObjectName(),
                            targetObj.get().getTargetSchemaName(),
                            targetObj.get().getTargetObjectName()
                        ));
                        return target;
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
            
            return Map.of("success", true, "dependencies", dependencies, 
                         "tableDependencies", tableDeps, "targetTableDependencies", targetTableDeps);
        } catch (Exception e) {
            log.error("Error analyzing source dependencies", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    private boolean isSupportedForDdlRetrieval(String objectType) {
        return objectType != null && (
            objectType.equals("TABLE") || 
            objectType.equals("VIEW") || 
            objectType.equals("FUNCTION") || 
            objectType.equals("PROCEDURE") ||
            objectType.equals("INDEX") ||
            objectType.equals("TRIGGER") ||
            objectType.equals("SEQUENCE") ||
            objectType.equals("CONSTRAINT")
        );
    }
    @PostMapping("/ddl/{objectId}/retrieve-source-db")
    @ResponseBody
    public String retrieveSourceDdlFromDb(@PathVariable Long objectId) {
        try {
            ddlService.retrieveSourceDdlFromDb(objectId);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error retrieving source DDL from DB", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/ddl/{objectId}/retrieve-target-db")
    @ResponseBody
    public String retrieveTargetDdlFromDb(@PathVariable Long objectId) {
        try {
            ddlService.retrieveTargetDdlFromDb(objectId);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error retrieving target DDL from DB", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/ddl/{objectId}/save-source-overwrite")
    @ResponseBody
    public String saveSourceUserOverwrite(@PathVariable Long objectId, @RequestBody String ddl) {
        try {
            ddlService.saveSourceUserOverwrite(objectId, ddl);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error saving source user overwrite", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/ddl/{objectId}/save-target-overwrite")
    @ResponseBody
    public String saveTargetUserOverwrite(@PathVariable Long objectId, @RequestBody String ddl) {
        try {
            ddlService.saveTargetUserOverwrite(objectId, ddl);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error saving target user overwrite", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/ddl/{objectId}/convert")
    @ResponseBody
    public String convertDdl(@PathVariable Long objectId, @RequestParam String sourceDdl, @RequestParam String instruction) {
        try {
            ddlService.convertDdl(objectId, sourceDdl, instruction);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error converting DDL", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/ddl/{objectId}/apply-to-target")
    @ResponseBody
    public String applyToTargetDatabase(@PathVariable Long objectId, 
                                       @RequestParam(defaultValue = "false") boolean dropBeforeRecreate,
                                       @RequestBody String ddl) {
        try {
            ddlService.applyToTargetDatabase(objectId, ddl, dropBeforeRecreate);
            updateTestStatus(objectId, 1, 2); // DDL success (ones place = 2)
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error applying DDL to target database", e);
            updateTestStatus(objectId, 1, 3); // DDL error (ones place = 3)
            return "ERROR: " + e.getMessage();
        }
    }
    
    private void updateTestStatus(Long objectId, int position, int status) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        String currentStatus = obj.getTestStatus();
        int statusValue = 111; // Default 3-digit format
        
        if (currentStatus != null && !currentStatus.isEmpty()) {
            try {
                String cleanStatus = currentStatus.replace("/", "").replace("-", "").trim();
                if (cleanStatus.length() == 3) {
                    statusValue = Integer.parseInt(cleanStatus);
                }
            } catch (NumberFormatException e) {
                statusValue = 111;
            }
        }
        
        // Update specific digit: position 1=ones, 10=tens, 100=hundreds
        // Position 100: PG Unit, Position 10: Comparison, Position 1: DDL
        int digit = (statusValue / position) % 10;
        statusValue = statusValue - (digit * position) + (status * position);
        
        obj.setTestStatus(String.valueOf(statusValue));
        repository.save(obj);
    }
    
    @GetMapping("/issues/{objectId}")
    public String issuesPage(@PathVariable Long objectId, Model model) {
        DatabaseObject obj = repository.findById(objectId).orElseThrow();
        model.addAttribute("object", obj);
        return "issues";
    }
    
    @PostMapping("/validation/{objectId}/generate")
    @ResponseBody
    public String generateTestCases(@PathVariable Long objectId) {
        try {
            log.info("Received request to generate test cases for object: {}", objectId);
            comparisonTestService.generateTestCases(objectId);
            log.info("Test cases generated successfully");
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Error generating test cases", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/execute-source")
    @ResponseBody
    public String executeSourceTest(@PathVariable Long testCaseId) {
        try {
            comparisonTestService.executeSourceTest(testCaseId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/convert")
    @ResponseBody
    public String convertToPostgreSQL(@PathVariable Long testCaseId) {
        try {
            comparisonTestService.convertToPostgreSQL(testCaseId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/execute-postgresql")
    @ResponseBody
    public String executePostgreSQLTest(@PathVariable Long testCaseId) {
        try {
            comparisonTestService.executePostgreSQLTest(testCaseId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/update")
    @ResponseBody
    public String updateTestCase(@PathVariable Long testCaseId, 
                                 @RequestParam String sourceSql,
                                 @RequestParam String targetSql) {
        try {
            comparisonTestService.updateTestCase(testCaseId, sourceSql, targetSql);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/fix/{dbType}")
    @ResponseBody
    public String fixTestCaseWithGenAI(@PathVariable Long testCaseId, 
                                       @PathVariable String dbType,
                                       @RequestParam(required = false, defaultValue = "") String additionalInstructions) {
        try {
            return comparisonTestService.fixWithGenAI(testCaseId, dbType, additionalInstructions);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/testcase/{testCaseId}/fix-target-code")
    @ResponseBody
    public String fixTargetCodeWithGenAI(@PathVariable Long testCaseId,
                                        @RequestParam(required = false, defaultValue = "") String additionalInstructions) {
        try {
            return comparisonTestService.fixTargetCodeWithGenAI(testCaseId, additionalInstructions);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/object/{objectId}/complete")
    @ResponseBody
    public String markComplete(@PathVariable Long objectId) {
        try {
            DatabaseObject obj = repository.findById(objectId).orElseThrow();
            obj.setIsComplete(true);
            repository.save(obj);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/object/{objectId}/ignore")
    @ResponseBody
    public String markIgnored(@PathVariable Long objectId) {
        try {
            DatabaseObject obj = repository.findById(objectId).orElseThrow();
            obj.setIsIgnored(true);
            repository.save(obj);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    @PostMapping("/test-case/{testCaseId}/delete")
    @ResponseBody
    public String deleteTestCase(@PathVariable Long testCaseId) {
        try {
            testCaseBaseService.deleteTestCase(testCaseId);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    private String getEngineName(String engine) {
        if (engine == null) return "Unknown";
        if ("oracle".equalsIgnoreCase(engine)) return "Oracle";
        if ("postgres".equalsIgnoreCase(engine) || "postgresql".equalsIgnoreCase(engine)) return "PostgreSQL";
        if ("sqlserver".equalsIgnoreCase(engine)) return "SQL Server";
        if ("mysql".equalsIgnoreCase(engine)) return "MySQL";
        return engine.toUpperCase();
    }
    
    private String getEngineIcon(String engine) {
        if (engine == null) return "unknown";
        if ("oracle".equalsIgnoreCase(engine)) return "oracle";
        if ("postgres".equalsIgnoreCase(engine) || "postgresql".equalsIgnoreCase(engine)) return "postgres";
        if ("sqlserver".equalsIgnoreCase(engine)) return "sqlserver";
        if ("mysql".equalsIgnoreCase(engine)) return "mysql";
        return engine.toLowerCase();
    }
    
    @GetMapping("/project/{projectId}/knowledge-base")
    public String projectKnowledgeBase(@PathVariable String projectId, Model model) throws Exception {
        Project project = projectRepository.findById(projectId).orElseThrow();
        
        String sourceEngine = project.getSourceEndpoint().getDbEngine();
        String targetEngine = project.getTargetEndpoint().getDbEngine();
        
        List<com.mma.testmanager.entity.KnowledgeBase> availableKbs = 
            knowledgeBaseRepository.findByEnginesAndActive(sourceEngine, targetEngine, true);
        
        List<com.mma.testmanager.entity.ProjectKnowledgeBase> projectKbs = 
            projectKnowledgeBaseRepository.findByProjectId(projectId);
        
        // Build set of disabled KB IDs
        java.util.Set<Long> disabledKbIds = new java.util.HashSet<>();
        for (var pkb : projectKbs) {
            if (!pkb.getEnabled()) {
                disabledKbIds.add(pkb.getKnowledgeBaseId());
            }
        }
        
        List<Map<String, Object>> kbList = new java.util.ArrayList<>();
        Map<Long, Map<String, Object>> kbDataMap = new java.util.HashMap<>();
        
        for (var kb : availableKbs) {
            Map<String, Object> kbData = new java.util.HashMap<>();
            kbData.put("id", kb.getId());
            kbData.put("name", kb.getName());
            kbData.put("sourceDbEngine", kb.getSourceDbEngine());
            kbData.put("targetDbEngine", kb.getTargetDbEngine());
            kbData.put("categories", kb.getCategories());
            kbData.put("priority", kb.getPriority());
            kbData.put("enabled", !disabledKbIds.contains(kb.getId())); // Enabled by default unless explicitly disabled
            kbData.put("content", kb.getContent());
            kbList.add(kbData);
            kbDataMap.put(kb.getId(), Map.of("name", kb.getName(), "content", kb.getContent()));
        }
        
        model.addAttribute("project", project);
        model.addAttribute("knowledgeBases", kbList);
        model.addAttribute("knowledgeBasesJson", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(kbDataMap));
        
        return "project-knowledge-base";
    }
    
    @PostMapping("/project/{projectId}/knowledge-base/{kbId}/toggle")
    @ResponseBody
    public Map<String, Object> toggleProjectKnowledgeBase(
            @PathVariable String projectId, 
            @PathVariable Long kbId,
            @RequestBody Map<String, Boolean> request) {
        
        Boolean enabled = request.get("enabled");
        
        var pkbOpt = projectKnowledgeBaseRepository.findByProjectIdAndKnowledgeBaseId(projectId, kbId);
        
        if (enabled) {
            // If enabled, delete the record (default is enabled)
            pkbOpt.ifPresent(projectKnowledgeBaseRepository::delete);
        } else {
            // If disabled, create/update the record
            var pkb = pkbOpt.orElse(new com.mma.testmanager.entity.ProjectKnowledgeBase());
            pkb.setProjectId(projectId);
            pkb.setKnowledgeBaseId(kbId);
            pkb.setEnabled(false);
            projectKnowledgeBaseRepository.save(pkb);
        }
        
        return Map.of("success", true);
    }
}
