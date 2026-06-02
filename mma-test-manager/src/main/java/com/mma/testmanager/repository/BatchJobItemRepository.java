package com.mma.testmanager.repository;

import com.mma.testmanager.entity.BatchJobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BatchJobItemRepository extends JpaRepository<BatchJobItem, Long> {
    List<BatchJobItem> findByBatchJobIdOrderById(Long batchJobId);
    List<BatchJobItem> findByBatchJobIdAndStatus(Long batchJobId, String status);
    int countByBatchJobIdAndStatus(Long batchJobId, String status);
}
