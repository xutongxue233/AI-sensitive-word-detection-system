package com.ai.moderation.controller;

import com.ai.moderation.dto.BatchIdsRequest;
import com.ai.moderation.dto.BatchOperationResponse;
import com.ai.moderation.dto.ExportTaskResponse;
import com.ai.moderation.service.ExportTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导出任务队列 REST 入口。
 */
@RestController
@RequestMapping("/api/v1/export-tasks")
public class ExportTaskController {
    private final ExportTaskService exportTaskService;

    public ExportTaskController(ExportTaskService exportTaskService) {
        this.exportTaskService = exportTaskService;
    }

    @GetMapping("/{id}")
    public ExportTaskResponse get(@PathVariable Long id) {
        return exportTaskService.get(id);
    }

    @PostMapping("/batch")
    public BatchOperationResponse batchEnqueue(@Valid @RequestBody BatchIdsRequest request) {
        return exportTaskService.batchEnqueue(request.ids());
    }
}
