package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "project_knowledge_bases", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "knowledge_base_id"}))
@Data
public class ProjectKnowledgeBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "project_id", nullable = false)
    private String projectId;
    
    @Column(name = "knowledge_base_id", nullable = false)
    private Long knowledgeBaseId;
    
    @Column(nullable = false)
    private Boolean enabled = true;
}
