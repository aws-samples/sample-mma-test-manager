package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
public class Project {
    @Id
    private String id;
    private String s3Path;
    private LocalDateTime createdAt;
    private String status;
    private Integer objectCount;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_endpoint_id")
    private DatabaseEndpoint sourceEndpoint;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_endpoint_id")
    private DatabaseEndpoint targetEndpoint;
    
    // Helper methods to get/set IDs
    public Long getSourceEndpointId() {
        return sourceEndpoint != null ? sourceEndpoint.getId() : null;
    }
    
    public void setSourceEndpointId(Long id) {
        if (id != null) {
            if (sourceEndpoint == null) {
                sourceEndpoint = new DatabaseEndpoint();
            }
            sourceEndpoint.setId(id);
        }
    }
    
    public Long getTargetEndpointId() {
        return targetEndpoint != null ? targetEndpoint.getId() : null;
    }
    
    public void setTargetEndpointId(Long id) {
        if (id != null) {
            if (targetEndpoint == null) {
                targetEndpoint = new DatabaseEndpoint();
            }
            targetEndpoint.setId(id);
        }
    }
    
    public String getSourceDbEngine() {
        return sourceEndpoint != null ? sourceEndpoint.getDbEngine() : null;
    }
    
    public String getTargetDbEngine() {
        return targetEndpoint != null ? targetEndpoint.getDbEngine() : null;
    }
}
