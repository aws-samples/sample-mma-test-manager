package com.mma.testmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "batch_jobs")
@Data
public class BatchJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String projectId;
    private String operation;
    private String status;      // QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    private int priority;       // 0 = highest, 99 = lowest
    private String submittedBy;
    private int totalItems;
    private int completedItems;
    private int failedItems;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "QUEUED";
    }
}
