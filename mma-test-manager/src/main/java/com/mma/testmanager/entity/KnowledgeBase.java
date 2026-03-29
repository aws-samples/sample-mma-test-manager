package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "knowledge_bases")
@Data
public class KnowledgeBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private String description;
    private String sourceDbEngine; // Standardized AWS engine names: oracle, postgres, sqlserver, mysql
    private String targetDbEngine; // Standardized AWS engine names: oracle, postgres, sqlserver, mysql
    
    @Column(columnDefinition = "TEXT")
    private String content; // The knowledge base content/instructions
    
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_base_categories", joinColumns = @JoinColumn(name = "knowledge_base_id"))
    @Column(name = "category")
    private Set<String> categories = new HashSet<>(); // DDL_CONVERSION, UNIT_TEST, COMPARISON_TEST, etc.
    
    private Boolean active = true;
    private Integer priority = 0; // Higher priority = applied first
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
