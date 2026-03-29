package com.mma.testmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.TestCase;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import com.mma.testmanager.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic base service for all test case operations.
 * Database-agnostic utilities that can be reused across any source-target pair.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestCaseBaseService {
    protected final TestCaseRepository testCaseRepository;
    protected final DatabaseObjectRepository objectRepository;
    protected final KiroCliCommonService kiroCliCommonService;
    protected final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${mma.kiro.max-retries:3}")
    public int maxRetries;
    
    
    public List<TestCase> findByObjectId(Long objectId) {
        return testCaseRepository.findByObjectIdOrderByTestNumberAsc(objectId);
    }
    
    public List<TestCase> findByObjectIdAndType(Long objectId, String testType) {
        return testCaseRepository.findByObjectIdOrderByTestNumberAsc(objectId).stream()
            .filter(tc -> testType.equals(tc.getTestType()))
            .toList();
    }
    
    public void deleteTestCase(Long testCaseId) {
        testCaseRepository.deleteById(testCaseId);
        log.info("Deleted test case {}", testCaseId);
    }
    
    public void deleteByProjectId(String projectId) {
        List<Long> objectIds = objectRepository.findByProjectId(projectId).stream()
            .map(DatabaseObject::getId).toList();
        for (Long objectId : objectIds) {
            testCaseRepository.deleteAll(testCaseRepository.findByObjectId(objectId));
        }
    }
    
    // Status calculation methods (generic for all source-target pairs)
    public void updateObjectStatus(Long objectId) {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        List<TestCase> allTests = testCaseRepository.findByObjectIdOrderByTestNumberAsc(objectId);
        
        List<TestCase> unitTests = allTests.stream()
            .filter(tc -> "UNIT".equals(tc.getTestType()))
            .toList();
        int unitStatus = calculateTestStatus(unitTests);
        
        List<TestCase> comparisonTests = allTests.stream()
            .filter(tc -> "COMPARISON".equals(tc.getTestType()))
            .toList();
        int comparisonStatus = calculateComparisonStatus(comparisonTests);
        
        String currentStatus = obj.getTestStatus();
        int ddlStatus = 1;
        if (currentStatus != null && !currentStatus.isEmpty()) {
            try {
                int statusValue = Integer.parseInt(currentStatus.replace("/", "").trim());
                ddlStatus = statusValue % 10;
            } catch (NumberFormatException e) {
                ddlStatus = 1;
            }
        }
        
        int newStatus = unitStatus * 100 + comparisonStatus * 10 + ddlStatus;
        obj.setTestStatus(String.valueOf(newStatus));
        objectRepository.save(obj);
    }
    
    private int calculateTestStatus(List<TestCase> tests) {
        if (tests.isEmpty()) return 1;
        
        long executedCount = tests.stream()
            .filter(tc -> tc.getTargetStatus() != null && 
                         !"CREATED".equals(tc.getTargetStatus()) &&
                         !"CONVERTED".equals(tc.getTargetStatus()))
            .count();
        
        if (executedCount == 0) return 1;
        
        long failedCount = tests.stream()
            .filter(tc -> "FAILURE".equals(tc.getTargetStatus()))
            .count();
        
        return failedCount > 0 ? 3 : 2;
    }
    
    private int calculateComparisonStatus(List<TestCase> tests) {
        if (tests.isEmpty()) return 1;
        
        long validatedCount = tests.stream()
            .filter(tc -> tc.getValidationResult() != null && 
                         !"INCOMPLETE".equals(tc.getValidationResult()))
            .count();
        
        if (validatedCount == 0) return 1;
        
        long failedCount = tests.stream()
            .filter(tc -> "FAIL".equals(tc.getValidationResult()))
            .count();
        
        return failedCount > 0 ? 3 : 2;
    }
    
    public List<TestCase> parseTestCases(String response, Long objectId) throws Exception {
        List<TestCase> testCases = new ArrayList<>();
        JsonNode array = objectMapper.readTree(response);
        
        for (JsonNode node : array) {
            TestCase tc = new TestCase();
            tc.setObjectId(objectId);
            tc.setTestNumber(node.path("test_number").asText());
            tc.setDescription(node.path("description").asText());
            tc.setScenario(node.path("scenario").asText());
            tc.setSourceSql(node.path("sql").asText());
            testCases.add(tc);
        }
        
        return testCases;
    }
}
