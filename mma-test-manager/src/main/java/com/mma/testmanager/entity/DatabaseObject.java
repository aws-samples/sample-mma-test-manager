package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "database_objects")
@Data
public class DatabaseObject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String projectId;
    
    // Source (Oracle)
    private String sourceSchemaName;
    private String sourceObjectType;
    private String sourcePackageName;
    private String sourceObjectName;
    
    @Column(columnDefinition = "TEXT")
    private String sourceDdl;
    
    @Column(columnDefinition = "TEXT")
    private String sourceDdlFromDb; // Retrieved from source database
    
    @Column(columnDefinition = "TEXT")
    private String sourceDdlUserOverwrite; // User manually edited
    
    // Target (PostgreSQL)
    private String targetSchemaName;
    private String targetObjectType;
    private String targetObjectName;
    
    @Column(columnDefinition = "TEXT")
    private String targetDdl;
    
    @Column(columnDefinition = "TEXT")
    private String targetDdlFromDb; // Retrieved from target database
    
    @Column(columnDefinition = "TEXT")
    private String targetDdlUserOverwrite; // User manually edited
    
    @Column(columnDefinition = "TEXT")
    private String targetDdlConverted; // AI converted
    
    @Column(columnDefinition = "TEXT")
    private String conversionIssues;
    
    @Column(columnDefinition = "TEXT")
    private String conversionActions; // JSON array of action items
    
    @Column(columnDefinition = "TEXT")
    private String actionItemsDescriptors; // JSON object with action descriptors
    
    private Boolean hasIssues;
    
    private Boolean isComplete;
    private Boolean isIgnored;
    
    @Column(columnDefinition = "TEXT")
    private String testOutput;
    
    private String testStatus;
    
    @Transient
    private String searchVector;
    
    public String getFormattedTestStatus(boolean isCodeObject) {
        if (testStatus == null || testStatus.isEmpty()) {
            return isCodeObject ? "D-U-C" : "D";
        }
        
        // Parse 3-digit status (e.g., "111", "211", "321")
        // Position 1: Unit (U), Position 2: Comparison (C), Position 3: DDL (D)
        String status = testStatus.trim();
        if (status.length() != 3) {
            status = "111";
        }
        
        char unit = status.charAt(0);
        char comparison = status.charAt(1);
        char ddl = status.charAt(2);
        
        if (isCodeObject) {
            return formatStatusChar('D', ddl) + "-" + 
                   formatStatusChar('U', unit) + "-" + 
                   formatStatusChar('C', comparison);
        } else {
            return formatStatusChar('D', ddl);
        }
    }
    
    private String formatStatusChar(char label, char statusDigit) {
        String color = switch (statusDigit) {
            case '2' -> "green";
            case '3' -> "red";
            default -> "black";
        };
        String weight = (statusDigit == '2' || statusDigit == '3') ? "bold" : "normal";
        return "<span style='color:" + color + ";font-weight:" + weight + "'>" + label + "</span>";
    }
    
    public String getTargetSchemaName() {
        return targetSchemaName != null ? targetSchemaName : (sourceSchemaName != null ? sourceSchemaName.toLowerCase() : "");
    }
    
    public String getTargetObjectName() {
        if (targetObjectName != null) {
            return targetObjectName;
        }
        // Fallback: construct from package and object name
        if (sourcePackageName != null && !sourcePackageName.isEmpty()) {
            return (sourcePackageName + "$" + sourceObjectName).toLowerCase();
        }
        return sourceObjectName != null ? sourceObjectName.toLowerCase() : "";
    }
    
    public String getTargetPackageName() {
        return sourcePackageName != null ? sourcePackageName.toLowerCase() : "";
    }
    
    public String getTargetObjectType() {
        return targetObjectType != null ? targetObjectType : sourceObjectType;
    }
}
