package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "test_cases")
@Data
public class TestCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Long objectId;
    private String testType; // COMPARISON or UNIT
    private String testNumber;
    private String description;
    private String scenario;
    
    @Column(columnDefinition = "TEXT")
    private String sourceSql;
    
    @Column(columnDefinition = "TEXT")
    private String targetSql;
    
    @Column(columnDefinition = "TEXT")
    private String sourceResult;
    
    @Column(columnDefinition = "TEXT")
    private String targetResult;
    
    @Column(columnDefinition = "TEXT")
    private String expectedResult;
    
    private String sourceStatus;
    private String targetStatus;
    private Long sourceExecutionTimeMs;
    private Long targetExecutionTimeMs;
    
    @Column(columnDefinition = "TEXT")
    private String sourceError;
    
    @Column(columnDefinition = "TEXT")
    private String targetError;
    
    private String validationResult;
    private Boolean resultsIdentical;
    
    @Column(columnDefinition = "TEXT")
    private String validationNotes;
    
    private LocalDateTime createdAt;
    private LocalDateTime sourceExecutedAt;
    private LocalDateTime targetExecutedAt;
}
