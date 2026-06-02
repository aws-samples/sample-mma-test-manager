package com.mma.testmanager.controller;

import com.mma.testmanager.entity.BatchJob;
import com.mma.testmanager.entity.BatchJobItem;
import com.mma.testmanager.entity.DatabaseObject;
import com.mma.testmanager.repository.BatchJobItemRepository;
import com.mma.testmanager.repository.BatchJobRepository;
import com.mma.testmanager.repository.DatabaseObjectRepository;
import com.mma.testmanager.service.BatchExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequiredArgsConstructor
public class BatchController {
    private final BatchExecutorService batchExecutorService;
    private final BatchJobRepository jobRepository;
    private final BatchJobItemRepository itemRepository;
    private final DatabaseObjectRepository objectRepository;
    private final com.mma.testmanager.repository.ProjectRepository projectRepository;

    @GetMapping("/project/{projectId}/batch")
    public String batchPage(@PathVariable String projectId,
                            @RequestParam(defaultValue = "") String operation,
                            @RequestParam(defaultValue = "") String search,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int pageSize,
                            @RequestParam(defaultValue = "0") int jobPage,
                            Model model) {
        var project = projectRepository.findById(projectId).orElseThrow();
        String sourceEngine = project.getSourceEndpoint() != null ? project.getSourceEndpoint().getDbEngine() : "unknown";
        String targetEngine = project.getTargetEndpoint() != null ? project.getTargetEndpoint().getDbEngine() : "unknown";

        // Get eligible objects based on operation
        Page<DatabaseObject> objectsPage = getEligibleObjects(projectId, operation, search, page, pageSize);

        // Get recent batch jobs (paginated, last 5)
        List<BatchJob> recentJobs = jobRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        int jobPageSize = 5;
        int totalJobPages = (recentJobs.size() + jobPageSize - 1) / jobPageSize;
        int fromIdx = Math.min(jobPage * jobPageSize, recentJobs.size());
        int toIdx = Math.min(fromIdx + jobPageSize, recentJobs.size());
        List<BatchJob> pagedJobs = recentJobs.subList(fromIdx, toIdx);

        model.addAttribute("projectId", projectId);
        model.addAttribute("project", project);
        model.addAttribute("operation", operation);
        model.addAttribute("search", search);
        model.addAttribute("objects", objectsPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", objectsPage.getTotalPages());
        model.addAttribute("totalElements", objectsPage.getTotalElements());
        model.addAttribute("recentJobs", pagedJobs);
        model.addAttribute("jobPage", jobPage);
        model.addAttribute("totalJobPages", totalJobPages);
        model.addAttribute("sourceEngine", getEngineName(sourceEngine));
        model.addAttribute("targetEngine", getEngineName(targetEngine));

        return "batch";
    }

    @PostMapping("/project/{projectId}/batch/submit")
    @ResponseBody
    public Map<String, Object> submitBatch(@PathVariable String projectId,
                                           @RequestParam String operation,
                                           @RequestParam List<Long> objectIds,
                                           @RequestParam(defaultValue = "50") int priority,
                                           java.security.Principal principal) {
        String user = principal != null ? principal.getName() : "unknown";
        BatchJob job = batchExecutorService.submitBatch(projectId, operation, objectIds, priority, user);
        return Map.of("jobId", job.getId(), "status", "QUEUED");
    }

    @GetMapping("/project/{projectId}/batch/{jobId}")
    public String batchDetail(@PathVariable String projectId, @PathVariable Long jobId, Model model) {
        BatchJob job = jobRepository.findById(jobId).orElseThrow();
        List<BatchJobItem> items = itemRepository.findByBatchJobIdOrderById(jobId);
        var project = projectRepository.findById(projectId).orElseThrow();

        model.addAttribute("projectId", projectId);
        model.addAttribute("project", project);
        model.addAttribute("job", job);
        model.addAttribute("items", items);
        return "batch-detail";
    }

    @GetMapping("/project/{projectId}/batch/{jobId}/status")
    @ResponseBody
    public Map<String, Object> getJobStatus(@PathVariable String projectId, @PathVariable Long jobId) {
        BatchJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return Map.of("error", "Job not found");

        List<BatchJobItem> items = itemRepository.findByBatchJobIdOrderById(jobId);
        int pending = 0, running = 0, success = 0, failed = 0, skipped = 0;
        for (BatchJobItem item : items) {
            switch (item.getStatus()) {
                case "PENDING" -> pending++;
                case "RUNNING" -> running++;
                case "SUCCESS" -> success++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
            }
        }
        int total = items.size();
        int progress = total > 0 ? (success + failed + skipped) * 100 / total : 0;

        List<Map<String, Object>> itemList = items.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("objectId", item.getObjectId());
            m.put("objectName", item.getObjectName());
            m.put("status", item.getStatus());
            m.put("message", item.getMessage());
            return m;
        }).toList();

        return Map.of(
            "jobId", job.getId(),
            "status", job.getStatus(),
            "operation", job.getOperation(),
            "progress", progress,
            "total", total,
            "pending", pending,
            "running", running,
            "success", success,
            "failed", failed,
            "items", itemList
        );
    }

    @PostMapping("/project/{projectId}/batch/{jobId}/cancel")
    @ResponseBody
    public Map<String, String> cancelJob(@PathVariable String projectId, @PathVariable Long jobId) {
        batchExecutorService.cancelJob(jobId);
        return Map.of("status", "CANCELLED");
    }

    private Page<DatabaseObject> getEligibleObjects(String projectId, String operation, String search, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(page, pageSize, Sort.by("sourceSchemaName", "sourceObjectType", "sourceObjectName"));

        // Filter by code objects for test operations
        List<String> codeTypes = List.of("PROCEDURE", "FUNCTION", "VIEW", "TRIGGER", "SQL_SCALAR_FUNCTION",
            "SQL_STORED_PROCEDURE", "SQL_INLINE_FUNCTION", "SQL_TABLE_VALUED_FUNCTION");

        boolean codeOnly = operation.startsWith("GENERATE_") || operation.startsWith("RUN_") ||
                           operation.equals("CONVERT_TESTS") || operation.equals("RESET_COMPARISON") ||
                           operation.equals("RESET_UNIT") ;

        if (search != null && !search.isBlank()) {
            PageRequest unsortedPageable = PageRequest.of(page, pageSize);
            return objectRepository.findByProjectIdWithFiltersAndSearch(
                projectId, codeOnly ? String.join(",", codeTypes) : "", "", true, true, search.trim(), unsortedPageable);
        }

        if (codeOnly) {
            return objectRepository.findByProjectIdWithFilters(projectId, codeTypes, null, true, true, pageable);
        }

        return objectRepository.findByProjectIdWithFilters(projectId, null, null, true, true, pageable);
    }

    private String getEngineName(String engine) {
        if (engine == null) return "Unknown";
        return switch (engine.toLowerCase()) {
            case "oracle" -> "Oracle";
            case "postgres", "postgresql" -> "PostgreSQL";
            case "sqlserver" -> "SQL Server";
            case "sybase" -> "Sybase ASE";
            case "mysql" -> "MySQL";
            default -> engine.toUpperCase();
        };
    }
}
