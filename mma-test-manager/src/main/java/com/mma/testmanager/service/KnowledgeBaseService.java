package com.mma.testmanager.service;

import com.mma.testmanager.entity.KnowledgeBase;
import com.mma.testmanager.entity.Project;
import com.mma.testmanager.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.mma.testmanager.repository.ProjectKnowledgeBaseRepository projectKnowledgeBaseRepository;
    
    public String getKnowledgeBaseContent(Project project, String category) {
        if (project.getSourceEndpoint() == null || project.getTargetEndpoint() == null) {
            return "";
        }
        
        String sourceEngine = project.getSourceEndpoint().getDbEngine();
        String targetEngine = project.getTargetEndpoint().getDbEngine();
        
        List<KnowledgeBase> kbs = knowledgeBaseRepository
            .findByEnginesAndCategoryAndActive(sourceEngine, targetEngine, category, true);
        
        if (kbs.isEmpty()) {
            log.debug("No knowledge base found for {}->{} category:{}", sourceEngine, targetEngine, category);
            return "";
        }
        
        // Get disabled KBs for this project
        List<com.mma.testmanager.entity.ProjectKnowledgeBase> projectKbs = 
            projectKnowledgeBaseRepository.findByProjectId(project.getId());
        java.util.Set<Long> disabledKbIds = new java.util.HashSet<>();
        for (var pkb : projectKbs) {
            if (!pkb.getEnabled()) {
                disabledKbIds.add(pkb.getKnowledgeBaseId());
            }
        }
        
        StringBuilder content = new StringBuilder();
        for (KnowledgeBase kb : kbs) {
            // Skip if disabled for this project
            if (disabledKbIds.contains(kb.getId())) {
                log.debug("Skipping disabled KB {} for project {}", kb.getName(), project.getId());
                continue;
            }
            content.append(kb.getContent()).append("\n\n");
        }
        
        return content.toString().trim();
    }
    
    public List<KnowledgeBase> search(String search, String sourceDb, String targetDb) {
        return knowledgeBaseRepository.search(
            search != null ? search.trim().toLowerCase() : null, 
            sourceDb, 
            targetDb
        );
    }
    
    public List<KnowledgeBase> getAllActive() {
        return knowledgeBaseRepository.findByActiveOrderByPriorityDesc(true);
    }
    
    public java.util.Optional<KnowledgeBase> findById(Long id) {
        return knowledgeBaseRepository.findById(id);
    }
    
    public KnowledgeBase save(KnowledgeBase kb) {
        return knowledgeBaseRepository.save(kb);
    }
    
    public void delete(Long id) {
        // First delete all project_knowledge_bases entries for this KB
        projectKnowledgeBaseRepository.deleteByKnowledgeBaseId(id);
        // Then delete the knowledge base itself
        knowledgeBaseRepository.deleteById(id);
    }
}
