package com.mma.testmanager.repository;

import com.mma.testmanager.entity.ProjectKnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectKnowledgeBaseRepository extends JpaRepository<ProjectKnowledgeBase, Long> {
    List<ProjectKnowledgeBase> findByProjectId(String projectId);
    Optional<ProjectKnowledgeBase> findByProjectIdAndKnowledgeBaseId(String projectId, Long knowledgeBaseId);
    void deleteByKnowledgeBaseId(Long knowledgeBaseId);
}
