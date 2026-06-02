package com.mma.testmanager.service;

import com.mma.testmanager.entity.BatchJob;
import com.mma.testmanager.entity.BatchJobItem;
import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.entity.TestCase;
import com.mma.testmanager.repository.BatchJobItemRepository;
import com.mma.testmanager.repository.BatchJobRepository;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import com.mma.testmanager.repository.TestCaseRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

@Service
@Slf4j
public class BatchExecutorService {
    private final BatchJobRepository jobRepository;
    private final BatchJobItemRepository itemRepository;
    private final DatabaseObjectRepository objectRepository;
    private final TestCaseRepository testCaseRepository;
    private final DdlService ddlService;
    private final DependencyService dependencyService;
    private final ComparisonTestService comparisonTestService;
    private final UnitTestService unitTestService;
    private final TestCaseBaseService testCaseBaseService;

    @Value("${mma.batch.concurrency:1}")
    private int globalConcurrency;

    @Value("${mma.batch.throttle-ms:1000}")
    private long throttleMs;

    private ExecutorService executor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    public BatchExecutorService(BatchJobRepository jobRepository, BatchJobItemRepository itemRepository,
                                DatabaseObjectRepository objectRepository, TestCaseRepository testCaseRepository,
                                DdlService ddlService, DependencyService dependencyService,
                                ComparisonTestService comparisonTestService,
                                UnitTestService unitTestService, TestCaseBaseService testCaseBaseService,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.objectRepository = objectRepository;
        this.testCaseRepository = testCaseRepository;
        this.ddlService = ddlService;
        this.dependencyService = dependencyService;
        this.comparisonTestService = comparisonTestService;
        this.unitTestService = unitTestService;
        this.testCaseBaseService = testCaseBaseService;
        this.txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    @PostConstruct
    void init() {
        globalConcurrency = Math.min(Math.max(globalConcurrency, 1), 3);
        executor = Executors.newFixedThreadPool(globalConcurrency);
        // Recover stuck jobs from previous restart
        recoverStuckJobs();
        // Poll for queued jobs every 2 seconds
        scheduler.scheduleWithFixedDelay(this::pollAndProcess, 5, 2, TimeUnit.SECONDS);
        log.info("BatchExecutorService started with global concurrency: {}", globalConcurrency);
    }

    private void recoverStuckJobs() {
        List<BatchJob> stuck = jobRepository.findByStatus("RUNNING");
        for (BatchJob job : stuck) {
            log.info("Recovering stuck job #{} - resetting to QUEUED", job.getId());
            job.setStatus("QUEUED");
            jobRepository.save(job);
            // Reset running items back to pending
            List<BatchJobItem> runningItems = itemRepository.findByBatchJobIdAndStatus(job.getId(), "RUNNING");
            for (BatchJobItem item : runningItems) {
                item.setStatus("PENDING");
                itemRepository.save(item);
            }
        }
        if (!stuck.isEmpty()) {
            log.info("Recovered {} stuck batch jobs", stuck.size());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    public void setConcurrency(int concurrency) {
        this.globalConcurrency = Math.min(Math.max(concurrency, 1), 3);
        executor.shutdown();
        executor = Executors.newFixedThreadPool(this.globalConcurrency);
        log.info("Global concurrency updated to: {}", this.globalConcurrency);
    }

    public int getConcurrency() {
        return globalConcurrency;
    }

    public BatchJob submitBatch(String projectId, String operation, List<Long> objectIds, int priority, String submittedBy) {
        BatchJob job = new BatchJob();
        job.setProjectId(projectId);
        job.setOperation(operation);
        job.setPriority(Math.min(Math.max(priority, 0), 99));
        job.setSubmittedBy(submittedBy);
        job.setTotalItems(objectIds.size());
        job.setStatus("CREATING");  // Don't set QUEUED until items are saved
        jobRepository.save(job);

        for (Long objectId : objectIds) {
            DatabaseObject obj = objectRepository.findById(objectId).orElse(null);
            BatchJobItem item = new BatchJobItem();
            item.setBatchJobId(job.getId());
            item.setObjectId(objectId);
            item.setObjectName(obj != null ? obj.getSourceSchemaName() + "." + obj.getSourceObjectName() : "unknown");
            itemRepository.save(item);
        }

        // Now make it visible to the scheduler
        job.setStatus("QUEUED");
        jobRepository.save(job);

        return job;
    }

    /** Poll for next QUEUED job by priority (lowest number first), then by creation time */
    private void pollAndProcess() {
        try {
            // Check if there's capacity (no RUNNING jobs beyond concurrency)
            List<BatchJob> running = jobRepository.findByStatus("RUNNING");
            if (running.size() >= globalConcurrency) return;

            // Get next queued job: order by priority ASC, createdAt ASC
            List<BatchJob> queued = jobRepository.findByStatus("QUEUED");
            if (queued.isEmpty()) return;

            // Sort by priority then createdAt
            queued.sort((a, b) -> {
                int cmp = Integer.compare(a.getPriority(), b.getPriority());
                return cmp != 0 ? cmp : a.getCreatedAt().compareTo(b.getCreatedAt());
            });

            BatchJob next = queued.get(0);
            next.setStatus("RUNNING");
            jobRepository.save(next);

            executor.submit(() -> processJob(next.getId()));
        } catch (Exception e) {
            log.error("Error in batch poll", e);
        }
    }

    private void processJob(Long jobId) {
        BatchJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        final BatchJob jobRef = job;

        List<BatchJobItem> items = itemRepository.findByBatchJobIdOrderById(jobId).stream()
            .filter(i -> "PENDING".equals(i.getStatus()))
            .toList();

        log.info("Processing batch job #{} with {} pending items", jobId, items.size());

        for (int idx = 0; idx < items.size(); idx++) {
            final BatchJobItem item = items.get(idx);
            // Check if job was cancelled
            BatchJob current = jobRepository.findById(jobId).orElse(null);
            if (current == null || "CANCELLED".equals(current.getStatus())) {
                log.info("Job #{} cancelled, stopping at item {}", jobId, idx);
                break;
            }
            log.info("Processing item {}/{}: {} (objectId={})", idx + 1, items.size(), item.getObjectName(), item.getObjectId());
            try {
                if (throttleMs > 0 && idx > 0) {
                    Thread.sleep(throttleMs);
                }
                txTemplate.executeWithoutResult(status -> processItem(jobRef, item));
            } catch (Exception e) {
                log.error("Unexpected error processing item {} in job #{}: {}", item.getObjectId(), jobId, e.getMessage(), e);
                item.setStatus("FAILED");
                item.setMessage("Loop error: " + e.getMessage());
                item.setCompletedAt(LocalDateTime.now());
                try { itemRepository.save(item); } catch (Exception saveErr) { log.error("Failed to save item status", saveErr); }
            }
        }

        log.info("Job #{} loop completed, finalizing...", jobId);

        // Finalize job
        job = jobRepository.findById(jobId).orElse(null);
        if (job != null && !"CANCELLED".equals(job.getStatus())) {
            // Mark any remaining PENDING items as SKIPPED
            List<BatchJobItem> remaining = itemRepository.findByBatchJobIdAndStatus(jobId, "PENDING");
            for (BatchJobItem r : remaining) {
                r.setStatus("SKIPPED");
                r.setMessage("Not processed - object type may not be supported for this operation");
                r.setCompletedAt(LocalDateTime.now());
                itemRepository.save(r);
            }
            int success = itemRepository.countByBatchJobIdAndStatus(jobId, "SUCCESS");
            int failed = itemRepository.countByBatchJobIdAndStatus(jobId, "FAILED");
            int skipped = itemRepository.countByBatchJobIdAndStatus(jobId, "SKIPPED");
            job.setCompletedItems(success);
            job.setFailedItems(failed + skipped);
            job.setStatus("COMPLETED");
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        }
    }

    public void processItem(BatchJob job, BatchJobItem item) {
        item.setStatus("RUNNING");
        item.setStartedAt(LocalDateTime.now());
        itemRepository.save(item);

        try {
            switch (job.getOperation()) {
                case "CONVERT_DDL" -> {
                    DatabaseObject obj = objectRepository.findById(item.getObjectId()).orElseThrow();
                    String sourceDdl = obj.getSourceDdlUserOverwrite() != null ? obj.getSourceDdlUserOverwrite() :
                                       obj.getSourceDdlFromDb() != null ? obj.getSourceDdlFromDb() : obj.getSourceDdl();
                    ddlService.convertDdl(item.getObjectId(), sourceDdl, null);
                }
                case "REFRESH_SOURCE_DDL" -> ddlService.retrieveSourceDdlFromDb(item.getObjectId());
                case "REFRESH_TARGET_DDL" -> ddlService.retrieveTargetDdlFromDb(item.getObjectId());
                case "GENERATE_COMPARISON" -> comparisonTestService.generateTestCases(item.getObjectId());
                case "RUN_SOURCE" -> runAllTestsForObject(item.getObjectId(), "COMPARISON", true);
                case "CONVERT_TESTS" -> convertAllTestsForObject(item.getObjectId());
                case "RUN_TARGET" -> runAllTestsForObject(item.getObjectId(), "COMPARISON", false);
                case "GENERATE_UNIT" -> unitTestService.generateUnitTests(item.getObjectId());
                case "RUN_UNIT" -> runAllTestsForObject(item.getObjectId(), "UNIT", false);
                case "RESET_COMPARISON" -> resetTests(item.getObjectId(), "COMPARISON");
                case "RESET_UNIT" -> resetTests(item.getObjectId(), "UNIT");
                
                default -> throw new UnsupportedOperationException("Unknown operation: " + job.getOperation());
            }
            item.setStatus("SUCCESS");
            item.setMessage("OK");
        } catch (Exception e) {
            item.setStatus("FAILED");
            item.setMessage(e.getMessage() != null ? e.getMessage() : "Unknown error");
            log.error("Batch item {} failed: {}", item.getObjectId(), e.getMessage());
        } catch (Throwable t) {
            item.setStatus("FAILED");
            item.setMessage("Unexpected: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            log.error("Batch item {} throwable: {}", item.getObjectId(), t.getMessage());
        }

        item.setCompletedAt(LocalDateTime.now());
        itemRepository.save(item);

        // Update job progress
        BatchJob j = jobRepository.findById(job.getId()).orElse(null);
        if (j != null) {
            j.setCompletedItems(itemRepository.countByBatchJobIdAndStatus(job.getId(), "SUCCESS"));
            j.setFailedItems(itemRepository.countByBatchJobIdAndStatus(job.getId(), "FAILED"));
            jobRepository.save(j);
        }
    }

    private void runAllTestsForObject(Long objectId, String testType, boolean isSource) throws Exception {
        List<TestCase> tests = testCaseBaseService.findByObjectIdAndType(objectId, testType);
        for (TestCase tc : tests) {
            if (isSource) {
                comparisonTestService.executeSourceTest(tc.getId());
            } else if ("COMPARISON".equals(testType)) {
                comparisonTestService.executePostgreSQLTest(tc.getId());
            } else {
                unitTestService.executePostgreSQLTest(tc.getId());
            }
        }
    }

    private void convertAllTestsForObject(Long objectId) throws Exception {
        List<TestCase> tests = testCaseBaseService.findByObjectIdAndType(objectId, "COMPARISON");
        for (TestCase tc : tests) {
            comparisonTestService.convertToPostgreSQL(tc.getId());
        }
    }

    private void resetTests(Long objectId, String testType) {
        List<TestCase> tests = testCaseBaseService.findByObjectIdAndType(objectId, testType);
        for (TestCase tc : tests) {
            testCaseRepository.delete(tc);
        }
        testCaseBaseService.updateObjectStatus(objectId);
    }

    public void cancelJob(Long jobId) {
        BatchJob job = jobRepository.findById(jobId).orElse(null);
        if (job != null && ("RUNNING".equals(job.getStatus()) || "QUEUED".equals(job.getStatus()))) {
            job.setStatus("CANCELLED");
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);

            List<BatchJobItem> pending = itemRepository.findByBatchJobIdAndStatus(jobId, "PENDING");
            for (BatchJobItem item : pending) {
                item.setStatus("SKIPPED");
                itemRepository.save(item);
            }
        }
    }
}
