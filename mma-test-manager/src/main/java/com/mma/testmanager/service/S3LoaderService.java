package com.mma.testmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.Project;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import com.mma.testmanager.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3LoaderService {
    private final DatabaseObjectRepository repository;
    private final ProjectRepository projectRepository;
    private final DatabaseEndpointService endpointService;
    private final OracleCommonService oracleService;
    private final SQLServerCommonService sqlServerService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> progressMap = new ConcurrentHashMap<>();
    
    public String getProgress(String projectId) {
        return progressMap.getOrDefault(projectId, "Starting...");
    }
    
    @Async
    public void loadFromS3Async(String projectId, String s3Path) {
        try {
            loadFromS3(projectId, s3Path);
        } catch (Exception e) {
            log.error("Error loading project", e);
            progressMap.put(projectId, "ERROR: " + e.getMessage());
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project != null) {
                project.setStatus("FAILED");
                projectRepository.save(project);
            }
        }
    }
    
    public String startLoad(String s3Path) {
        String projectId = UUID.randomUUID().toString();
        Project project = new Project();
        project.setId(projectId);
        project.setS3Path(s3Path);
        project.setCreatedAt(LocalDateTime.now());
        project.setStatus("LOADING");
        projectRepository.save(project);
        
        // Parse and create database endpoints from S3 metadata
        endpointService.parseAndCreateEndpoints(projectId, s3Path);
        
        loadFromS3Async(projectId, s3Path);
        return projectId;
    }
    
    private void loadFromS3(String projectId, String s3Path) throws Exception {
        s3Path = s3Path.replace("s3://", "").replaceAll("/+$", "");
        String[] parts = s3Path.split("/", 2);
        String bucket = parts[0];
        String prefix = parts.length > 1 ? parts[1] + "/" : "";
        
        log.info("Starting S3 load from bucket: {}, prefix: {}", bucket, prefix);
        progressMap.put(projectId, "Connecting to S3...");
        
        // Detect source database engine
        Project project = projectRepository.findById(projectId).orElseThrow();
        String sourceEngine = "unknown";
        if (project.getSourceEndpointId() != null) {
            sourceEngine = endpointService.getEndpointById(project.getSourceEndpointId())
                .map(ep -> ep.getDbEngine())
                .orElse("unknown");
        }
        log.info("Detected source database engine: {}", sourceEngine);
        
        try (S3Client s3 = S3Client.builder().build()) {
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix).build();
            
            List<S3Object> objects = s3.listObjectsV2(req).contents();
            log.info("Found {} objects in S3", objects.size());
            progressMap.put(projectId, "Found " + objects.size() + " objects. Processing...");
            
            Map<String, DatabaseObject> objectMap = new HashMap<>();
            Map<String, String> sourceIdToMapKey = new HashMap<>();
            int processed = 0;
            int sourceFilesProcessed = 0;
            
            // First pass: load source objects and build ID mapping
            log.info("Loading source objects from S3...");
            for (S3Object obj : objects) {
                String key = obj.key();
                if (key.contains("/s-")) {
                    sourceFilesProcessed++;
                    if (sourceFilesProcessed % 50 == 0) {
                        log.info("Processing source files: {}, loaded {} objects so far", sourceFilesProcessed, objectMap.size());
                    }
                    
                    String content = s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket).key(key).build()).asUtf8String();
                    
                    JsonNode json = objectMapper.readTree(content);
                    JsonNode contentArray = json.path("content");
                    if (contentArray.isArray() && contentArray.size() > 0) {
                        JsonNode item = contentArray.get(0);
                        String metaType = item.path("meta-type").asText().toUpperCase();
                        
                        // Skip metadata/folder object types based on source database engine
                        if ("oracle".equalsIgnoreCase(sourceEngine) && oracleService.isOracleMetadataType(metaType)) {
                            continue;
                        }
                        if ("sqlserver".equalsIgnoreCase(sourceEngine) && sqlServerService.isSQLServerMetadataType(metaType)) {
                            continue;
                        }
                        
                        JsonNode locator = item.path("locator");
                        String schema = locator.path("schema-name").asText();
                        String name = item.path("name").asText();
                        String sourceId = item.path("id").asText();
                        
                        if (!metaType.isEmpty() && !name.isEmpty() && !sourceId.isEmpty()) {
                            // Use sourceId as the unique key to support overloading
                            String mapKey = sourceId.toUpperCase();
                            
                            DatabaseObject dbObj = new DatabaseObject();
                            dbObj.setProjectId(projectId);
                            dbObj.setSourceObjectType(metaType);
                            dbObj.setSourceSchemaName(schema);
                            dbObj.setSourceObjectName(name);
                            dbObj.setSourcePackageName(locator.path("package-name").asText(""));
                            dbObj.setSourceDdl(item.path("sql").asText());
                            
                            objectMap.put(mapKey, dbObj);
                            
                            // Map source ID to mapKey for target matching
                            sourceIdToMapKey.put(sourceId.toUpperCase(), mapKey);
                            
                            // Extract target mapping from synchronization_object
                            JsonNode syncObj = item.path("synchronization_object");
                            if (!syncObj.isMissingNode() && !syncObj.isNull()) {
                                String targetId = syncObj.path("name").asText();
                                if (!targetId.isEmpty()) {
                                    // Store for logging only
                                    if (sourceIdToMapKey.size() <= 5) {
                                        log.info("Sample source: id='{}', maps to target='{}'", sourceId, targetId);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            log.info("Loaded {} source objects from {} source files", objectMap.size(), sourceFilesProcessed);
            
            // Second pass: load target objects and match by synchronization_object
            int matchedCount = 0;
            int unmatchedCount = 0;
            for (S3Object obj : objects) {
                String key = obj.key();
                if (key.contains("/t-")) {
                    processed++;
                    if (processed % 10 == 0) {
                        String progress = String.format("Processing %d/%d (%.1f%%), matched: %d, unmatched: %d", 
                            processed, objects.size(), (processed * 100.0 / objects.size()), matchedCount, unmatchedCount);
                        progressMap.put(projectId, progress);
                        log.info(progress);
                    }
                    
                    String content = s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket).key(key).build()).asUtf8String();
                    
                    JsonNode json = objectMapper.readTree(content);
                    JsonNode contentArray = json.path("content");
                    if (contentArray.isArray() && contentArray.size() > 0) {
                        JsonNode item = contentArray.get(0);
                        
                        // Get the source object ID from synchronization_object
                        JsonNode syncObj = item.path("synchronization_object");
                        String sourceId = "";
                        if (!syncObj.isMissingNode() && !syncObj.isNull()) {
                            sourceId = syncObj.path("name").asText();
                        }
                        
                        if (matchedCount + unmatchedCount <= 5) {
                            log.info("Sample target from key='{}', sourceId='{}'", key, sourceId);
                        }
                        
                        String sourceMapKey = sourceIdToMapKey.get(sourceId.toUpperCase());
                        if (sourceMapKey != null) {
                            matchedCount++;
                            if (matchedCount <= 3) {
                                log.info("MATCH: sourceId='{}' -> sourceMapKey='{}'", sourceId, sourceMapKey);
                            }
                        } else {
                            unmatchedCount++;
                            if (unmatchedCount <= 3) {
                                log.info("NO MATCH: sourceId='{}' from key='{}'", sourceId, key);
                            }
                        }
                        
                        if (sourceMapKey != null) {
                            DatabaseObject dbObj = objectMap.get(sourceMapKey);
                            if (dbObj != null) {
                                String targetDdl = item.path("sql").asText("");
                                if (!targetDdl.isEmpty()) {
                                    dbObj.setTargetDdl(targetDdl);
                                    if (matchedCount <= 3) {
                                        log.info("SET TARGET DDL for '{}', length={}", sourceMapKey, targetDdl.length());
                                    }
                                } else {
                                    if (matchedCount <= 5) {
                                        log.warn("Empty target DDL for key: {}, sourceMapKey: {}, sourceId: {}", key, sourceMapKey, sourceId);
                                    }
                                }
                                
                                // Extract target schema and object name
                                JsonNode locator = item.path("locator");
                                if (!locator.isMissingNode()) {
                                    String targetSchema = locator.path("schema-name").asText("");
                                    String targetName = locator.path("name").asText("");
                                    String metaType = item.path("meta-type").asText("");
                                    
                                    // For CONSTRAINT, use constraint-name field
                                    if ("CONSTRAINT".equalsIgnoreCase(metaType)) {
                                        targetName = locator.path("constraint-name").asText("");
                                    }
                                    // For INDEX, use index-name field
                                    else if ("INDEX".equalsIgnoreCase(metaType)) {
                                        targetName = locator.path("index-name").asText("");
                                    }
                                    // For DOMAIN, use domain-name field
                                    else if ("DOMAIN".equalsIgnoreCase(metaType)) {
                                        targetName = locator.path("domain-name").asText("");
                                    }
                                    // For tables, use table-name field
                                    else if (targetName.isEmpty()) {
                                        targetName = locator.path("table-name").asText("");
                                    }
                                    
                                    if (!targetSchema.isEmpty()) {
                                        dbObj.setTargetSchemaName(targetSchema);
                                    }
                                    if (!targetName.isEmpty()) {
                                        dbObj.setTargetObjectName(targetName);
                                    }
                                    if (!metaType.isEmpty()) {
                                        dbObj.setTargetObjectType(metaType);
                                    }
                                }
                                
                                // Check for conversion issues and store action items
                                JsonNode issues = item.path("action-items");
                                if (issues.isArray() && issues.size() > 0) {
                                    StringBuilder issueText = new StringBuilder();
                                    for (JsonNode issue : issues) {
                                        issueText.append(issue.path("description").asText()).append("\n");
                                    }
                                    dbObj.setConversionIssues(issueText.toString());
                                    dbObj.setConversionActions(issues.toString());
                                    dbObj.setHasIssues(true);
                                }
                            }
                        }
                    }
                }
            }
            
            log.info("Completed loading. Total objects: {}, Matched targets: {}, Unmatched targets: {}", 
                objectMap.size(), matchedCount, unmatchedCount);
            
            // Third pass: load action-items from action-items folder
            progressMap.put(projectId, "Loading action items...");
            java.util.concurrent.atomic.AtomicInteger actionItemsLoaded = new java.util.concurrent.atomic.AtomicInteger(0);
            Map<String, JsonNode> descriptorsCache = new HashMap<>();
            
            for (S3Object obj : objects) {
                String key = obj.key();
                if (key.contains("/action-items/")) {
                    try {
                        String content = s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket).key(key).build()).asUtf8String();
                        
                        JsonNode json = objectMapper.readTree(content);
                        
                        // Extract actionItemsDescriptors if present (inside content object)
                        JsonNode contentNode = json.path("content");
                        JsonNode descriptors = contentNode.path("actionItemsDescriptors");
                        if (descriptors.isMissingNode()) {
                            descriptors = json.path("actionItemsDescriptors"); // fallback to root level
                        }
                        
                        // Navigate through the JSON structure to find objects with messageActions
                        processActionItemsRecursively(json, objectMap, actionItemsLoaded, descriptors);
                        
                    } catch (Exception e) {
                        log.warn("Error loading action items from {}: {}", key, e.getMessage());
                    }
                }
            }
            log.info("Loaded action items for {} objects", actionItemsLoaded.get());
            
            progressMap.put(projectId, "Saving to database...");
            log.info("Saving {} objects to database...", objectMap.size());
            int loaded = 0;
            int skipped = 0;
            for (DatabaseObject dbObj : objectMap.values()) {
                if (dbObj.getSourceObjectName() != null && !dbObj.getSourceObjectName().isEmpty()) {
                    repository.save(dbObj);
                    loaded++;
                    if (loaded % 50 == 0) {
                        log.info("Saved {}/{} objects to database", loaded, objectMap.size());
                    }
                } else {
                    skipped++;
                }
            }
            log.info("Database save completed: {} objects saved", loaded);
            
            project = projectRepository.findById(projectId).orElseThrow();
            project.setStatus("COMPLETED");
            project.setObjectCount(loaded);
            projectRepository.save(project);
            
            // Final summary
            int objectsWithIssues = (int) objectMap.values().stream()
                .filter(obj -> obj.getHasIssues() != null && obj.getHasIssues())
                .count();
            int objectsWithTargetDdl = (int) objectMap.values().stream()
                .filter(obj -> obj.getTargetDdl() != null && !obj.getTargetDdl().isEmpty())
                .count();
            
            log.info("Load from S3 completed for ProjectId: {}", projectId);
            String summary = String.format(
                "\n=== Load Summary ===\n" +
                "Source objects loaded: %d\n" +
                "Target files processed: %d\n" +
                "  - Matched to source: %d\n" +
                "  - Unmatched: %d\n" +
                "Objects with target DDL: %d\n" +
                "Objects with conversion issues: %d\n" +
                "Objects with action items: %d\n" +
                "Objects saved to database: %d\n" +
                "Objects skipped (no name): %d\n" +
                "===================",
                objectMap.size(), processed, matchedCount, unmatchedCount,
                objectsWithTargetDdl, objectsWithIssues, actionItemsLoaded.get(),
                loaded, skipped
            );
            
            log.info(summary);
            progressMap.put(projectId, "COMPLETED: " + loaded + " objects loaded");
        }
    }
    
    private void processActionItemsRecursively(JsonNode node, Map<String, DatabaseObject> objectMap, 
                                              java.util.concurrent.atomic.AtomicInteger actionItemsLoaded,
                                              JsonNode descriptors) {
        if (node.isObject()) {
            // Check if this node has statistic.messageActions
            JsonNode statistic = node.path("statistic");
            if (!statistic.isMissingNode()) {
                JsonNode messageActions = statistic.path("messageActions");
                if (messageActions.isArray() && messageActions.size() > 0) {
                    // Get the object name from the node
                    String nodeName = node.path("name").asText();
                    if (!nodeName.isEmpty()) {
                        // Extract schema and object name from the full path
                        // Format: Servers.XXX.Schemas.DEMO.Functions.GET_CUSTOMER_LIFETIME_VALUE
                        String[] parts = nodeName.split("\\.");
                        if (parts.length >= 4) {
                            String objectType = parts[parts.length - 2].toUpperCase();
                            String objectName = parts[parts.length - 1];
                            String schema = "";
                            
                            // Find schema name
                            for (int i = 0; i < parts.length - 2; i++) {
                                if (parts[i].equals("Schemas") && i + 1 < parts.length) {
                                    schema = parts[i + 1];
                                    break;
                                }
                            }
                            
                            // Map object type names
                            String mappedType = switch(objectType) {
                                case "FUNCTIONS" -> "FUNCTION";
                                case "PROCEDURES" -> "PROCEDURE";
                                case "TABLES" -> "TABLE";
                                case "VIEWS" -> "VIEW";
                                case "PACKAGES" -> "PACKAGE";
                                default -> objectType;
                            };
                            
                            // Build the source ID to match objectMap keys
                            // Format: Servers.GJQFF6QMGNGX7NW2WFKYEAOA6I.Schemas.DEMO.Functions.GET_CUSTOMER_FULL_NAME
                            String sourceId = nodeName.toUpperCase();
                            DatabaseObject dbObj = objectMap.get(sourceId);
                            
                            if (dbObj != null) {
                                dbObj.setConversionActions(messageActions.toString());
                                if (!descriptors.isMissingNode()) {
                                    dbObj.setActionItemsDescriptors(descriptors.toString());
                                }
                                dbObj.setHasIssues(true);
                                actionItemsLoaded.incrementAndGet();
                                log.debug("Loaded action items for: {}", sourceId);
                            }
                        }
                    }
                }
            }
            
            // Recursively process all fields
            node.fields().forEachRemaining(entry -> 
                processActionItemsRecursively(entry.getValue(), objectMap, actionItemsLoaded, descriptors)
            );
        } else if (node.isArray()) {
            // Process array elements
            node.forEach(element -> 
                processActionItemsRecursively(element, objectMap, actionItemsLoaded, descriptors)
            );
        }
    }
    
}
