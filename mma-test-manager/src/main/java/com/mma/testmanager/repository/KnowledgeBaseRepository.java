package com.mma.testmanager.repository;

import com.mma.testmanager.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    @Query("SELECT DISTINCT kb FROM KnowledgeBase kb JOIN kb.categories c " +
           "WHERE kb.sourceDbEngine = :sourceDbEngine " +
           "AND kb.targetDbEngine = :targetDbEngine " +
           "AND c = :category " +
           "AND kb.active = :active " +
           "ORDER BY kb.priority DESC")
    List<KnowledgeBase> findByEnginesAndCategoryAndActive(
        @Param("sourceDbEngine") String sourceDbEngine, 
        @Param("targetDbEngine") String targetDbEngine, 
        @Param("category") String category, 
        @Param("active") Boolean active);
    
    @Query("SELECT kb FROM KnowledgeBase kb " +
           "WHERE kb.sourceDbEngine = :sourceDbEngine " +
           "AND kb.targetDbEngine = :targetDbEngine " +
           "AND kb.active = :active " +
           "ORDER BY kb.priority DESC")
    List<KnowledgeBase> findByEnginesAndActive(
        @Param("sourceDbEngine") String sourceDbEngine, 
        @Param("targetDbEngine") String targetDbEngine, 
        @Param("active") Boolean active);
    
    @Query("SELECT kb FROM KnowledgeBase kb " +
           "WHERE (:search IS NULL OR :search = '' OR LOWER(kb.name) LIKE CONCAT('%', :search, '%')) " +
           "AND (:sourceDb IS NULL OR :sourceDb = '' OR kb.sourceDbEngine = :sourceDb OR kb.targetDbEngine = :sourceDb) " +
           "ORDER BY kb.active DESC, kb.priority DESC")
    List<KnowledgeBase> search(
        @Param("search") String search,
        @Param("sourceDb") String sourceDb,
        @Param("targetDb") String targetDb);
    
    List<KnowledgeBase> findByActiveOrderByPriorityDesc(Boolean active);
}
