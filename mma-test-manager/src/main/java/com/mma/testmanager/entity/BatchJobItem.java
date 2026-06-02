package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "batch_job_items")
@Data
public class BatchJobItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchJobId;
    private Long objectId;
    private String objectName;
    private String status;      // PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
    @jakarta.persistence.Column(columnDefinition = "TEXT")
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (status == null) status = "PENDING";
    }
}
