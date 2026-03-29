package com.mma.testmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mma.testmanager.entity.DatabaseEndpoint;
import com.mma.testmanager.entity.Project;
import com.mma.testmanager.repository.DatabaseEndpointRepository;
import com.mma.testmanager.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.InputStream;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseEndpointService {
    private final DatabaseEndpointRepository endpointRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public java.util.Optional<DatabaseEndpoint> getEndpointById(Long id) {
        return endpointRepository.findById(id);
    }
    
    /**
     * Parse S3 metadata files and create database endpoints for a project.
     * Extracts vendor information from s-xxx/xxx (source) and t-yyy/yyy (target) files.
     */
    public void parseAndCreateEndpoints(String projectId, String s3Path) {
        try {
            Project project = projectRepository.findById(projectId).orElseThrow();
            
            // Parse source endpoint from s-xxx/xxx file
            DatabaseEndpoint sourceEndpoint = parseEndpointFromS3(s3Path, "SOURCE");
            if (sourceEndpoint != null) {
                sourceEndpoint = endpointRepository.save(sourceEndpoint);
                project.setSourceEndpointId(sourceEndpoint.getId());
            }
            
            // Parse target endpoint from t-yyy/yyy file
            DatabaseEndpoint targetEndpoint = parseEndpointFromS3(s3Path, "TARGET");
            if (targetEndpoint != null) {
                targetEndpoint = endpointRepository.save(targetEndpoint);
                project.setTargetEndpointId(targetEndpoint.getId());
            }
            
            projectRepository.save(project);
            log.info("Created endpoints for project {}: source={}, target={}", 
                projectId, sourceEndpoint != null ? sourceEndpoint.getDbEngine() : "null",
                targetEndpoint != null ? targetEndpoint.getDbEngine() : "null");
        } catch (Exception e) {
            log.error("Failed to parse endpoints for project {}: {}", projectId, e.getMessage());
        }
    }
    
    private DatabaseEndpoint parseEndpointFromS3(String s3Path, String endpointType) {
        try (S3Client s3Client = S3Client.builder().build()) {
            String[] parts = s3Path.replace("s3://", "").split("/", 2);
            String bucket = parts[0];
            String prefix = parts.length > 1 ? parts[1] : "";
            
            // Step 1: Read s-server or t-server to get connection name
            String serverFile = endpointType.equals("SOURCE") ? "s-server" : "t-server";
            String serverKey = prefix.isEmpty() ? serverFile : prefix + "/" + serverFile;
            
            log.info("Step 1: Loading {} from s3://{}/{}", endpointType, bucket, serverKey);
            
            GetObjectRequest serverRequest = GetObjectRequest.builder()
                .bucket(bucket).key(serverKey).build();
            
            String connectionName;
            try (InputStream is = s3Client.getObject(serverRequest)) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode children = root.path("content").get(0).path("children");
                if (!children.isArray() || children.size() == 0) {
                    log.error("No children found in {}", serverKey);
                    return null;
                }
                connectionName = children.get(0).path("name").asText();
                log.info("Found connection name: {}", connectionName);
            }
            
            // Step 2: Read detailed metadata from s-{name}/{name} or t-{name}/{name}
            String filePrefix = endpointType.equals("SOURCE") ? "s-" : "t-";
            String detailKey = prefix.isEmpty() 
                ? filePrefix + connectionName + "/" + connectionName
                : prefix + "/" + filePrefix + connectionName + "/" + connectionName;
            
            log.info("Step 2: Loading details from s3://{}/{}", bucket, detailKey);
            
            GetObjectRequest detailRequest = GetObjectRequest.builder()
                .bucket(bucket).key(detailKey).build();
            
            try (InputStream is = s3Client.getObject(detailRequest)) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode content = root.path("content");
                
                if (content.isArray() && content.size() > 0) {
                    JsonNode connection = content.get(0);
                    JsonNode serverInfo = connection.path("server_info");
                    JsonNode properties = connection.path("properties").path("Category");
                    
                    DatabaseEndpoint endpoint = new DatabaseEndpoint();
                    endpoint.setEndpointType(endpointType);
                    endpoint.setVendorName(serverInfo.path("vendorName").asText());
                    endpoint.setVendorEngine(serverInfo.path("vendorEngine").asText());
                    endpoint.setVendorEngineVersion(serverInfo.path("vendorEngineVersion").asText());
                    endpoint.setVendorEdition(serverInfo.path("vendorEdition").asText(""));
                    endpoint.setConnectionData(properties.path("connection-data").asText(""));
                    endpoint.setDbEngine(normalizeEngine(serverInfo.path("vendorName").asText()));
                    endpoint.setCreatedAt(LocalDateTime.now());
                    
                    log.info("Parsed {} endpoint: engine={}, connection={}", 
                        endpointType, endpoint.getDbEngine(), endpoint.getConnectionData());
                    return endpoint;
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse {} endpoint from S3: {}", endpointType, e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * Normalize vendor names to AWS standard engine names.
     */
    private String normalizeEngine(String vendorName) {
        if (vendorName == null) return "unknown";
        
        return switch (vendorName.toUpperCase()) {
            case "ORACLE" -> "oracle";
            case "POSTGRESQL", "AURORA_POSTGRESQL" -> "postgres";
            case "MYSQL", "AURORA_MYSQL" -> "mysql";
            case "MSSQL" -> "sqlserver";
            default -> vendorName.toLowerCase();
        };
    }
}
