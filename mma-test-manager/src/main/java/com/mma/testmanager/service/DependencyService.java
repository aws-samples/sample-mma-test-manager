package com.mma.testmanager.service;

import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.Dependency;
import com.mma.testmanager.entity.Project;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DependencyService {
    private final DatabaseObjectRepository objectRepository;
    private final com.mma.testmanager.repository.ProjectRepository projectRepository;
    private final OracleCommonService oracleService;
    private final SQLServerCommonService sqlServerService;

    public List<Dependency> getObjectDependencies(Long objectId) throws Exception {
        DatabaseObject obj = objectRepository.findById(objectId).orElseThrow();
        Project project = projectRepository.findById(obj.getProjectId()).orElseThrow();
        String sourceEngine = project.getSourceEndpoint().getDbEngine();
        
        if ("ORACLE".equalsIgnoreCase(sourceEngine)) {
            return oracleService.getSourceDependencies(objectId);
        } else if ("SQLSERVER".equalsIgnoreCase(sourceEngine)) {
            return sqlServerService.getSourceDependencies(objectId);
        }
        
        throw new UnsupportedOperationException("Source database engine not supported: " + sourceEngine);
    }
}

