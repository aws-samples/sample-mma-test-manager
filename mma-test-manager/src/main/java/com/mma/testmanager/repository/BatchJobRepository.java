package com.mma.testmanager.repository;

import com.mma.testmanager.entity.BatchJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BatchJobRepository extends JpaRepository<BatchJob, Long> {
    List<BatchJob> findByProjectIdOrderByCreatedAtDesc(String projectId);
    List<BatchJob> findByStatus(String status);
}
