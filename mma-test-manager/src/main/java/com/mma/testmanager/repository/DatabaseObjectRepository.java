package com.mma.testmanager.repository;

import com.mma.testmanager.entity.DatabaseObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface DatabaseObjectRepository extends JpaRepository<DatabaseObject, Long> {
    List<DatabaseObject> findByProjectId(String projectId);
    List<DatabaseObject> findByProjectIdAndSourceObjectType(String projectId, String sourceObjectType);
    List<DatabaseObject> findByProjectIdAndSourceObjectTypeIn(String projectId, List<String> sourceObjectTypes);
    List<DatabaseObject> findByProjectIdAndSourceObjectTypeNotIn(String projectId, List<String> sourceObjectTypes);
    List<DatabaseObject> findByProjectIdAndSourceSchemaNameAndSourceObjectName(String projectId, String sourceSchemaName, String sourceObjectName);
    
    @Modifying
    void deleteByProjectId(String projectId);
    
    @Query("SELECT d FROM DatabaseObject d WHERE d.projectId = :projectId " +
           "AND (:objectTypes IS NULL OR d.sourceObjectType IN :objectTypes) " +
           "AND (:excludeTypes IS NULL OR d.sourceObjectType NOT IN :excludeTypes) " +
           "AND (:showComplete = true OR d.isComplete IS NULL OR d.isComplete = false) " +
           "AND (:showIgnored = true OR d.isIgnored IS NULL OR d.isIgnored = false) " +
           "ORDER BY d.sourceObjectType, d.sourceSchemaName, d.sourcePackageName, d.sourceObjectName")
    Page<DatabaseObject> findByProjectIdWithFilters(
        @Param("projectId") String projectId,
        @Param("objectTypes") List<String> objectTypes,
        @Param("excludeTypes") List<String> excludeTypes,
        @Param("showComplete") boolean showComplete,
        @Param("showIgnored") boolean showIgnored,
        Pageable pageable
    );
    
    @Query(value = "SELECT * FROM database_objects d WHERE d.project_id = :projectId " +
           "AND (NULLIF(:includeTypes, '') IS NULL OR d.source_object_type = ANY(string_to_array(:includeTypes, ','))) " +
           "AND (NULLIF(:excludeTypes, '') IS NULL OR d.source_object_type <> ALL(string_to_array(:excludeTypes, ','))) " +
           "AND (:showComplete = true OR d.is_complete IS NULL OR d.is_complete = false) " +
           "AND (:showIgnored = true OR d.is_ignored IS NULL OR d.is_ignored = false) " +
           "AND (d.search_vector @@ plainto_tsquery('simple', :search) " +
           "     OR d.source_object_name ILIKE '%' || :search || '%' " +
           "     OR d.target_object_name ILIKE '%' || :search || '%') " +
           "ORDER BY d.source_object_type, d.source_schema_name, d.source_package_name, d.source_object_name",
           nativeQuery = true)
    Page<DatabaseObject> findByProjectIdWithFiltersAndSearch(
        @Param("projectId") String projectId,
        @Param("includeTypes") String objectTypes,
        @Param("excludeTypes") String excludeTypes,
        @Param("showComplete") boolean showComplete,
        @Param("showIgnored") boolean showIgnored,
        @Param("search") String search,
        Pageable pageable
    );
}
