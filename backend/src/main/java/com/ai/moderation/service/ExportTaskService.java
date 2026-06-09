package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.ExportTask;
import com.ai.moderation.domain.ExportTaskStatus;
import com.ai.moderation.dto.BatchItemResponse;
import com.ai.moderation.dto.BatchOperationResponse;
import com.ai.moderation.dto.ExportResponse;
import com.ai.moderation.dto.ExportTaskResponse;
import com.ai.moderation.repository.ExportTaskRepository;
import com.ai.moderation.repository.VideoFileRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 导出任务队列。接口层只负责入队,真正 FFmpeg 导出在单线程后台队列串行执行。
 */
@Service
public class ExportTaskService {
    private static final Logger log = LoggerFactory.getLogger(ExportTaskService.class);

    private final ExportTaskRepository taskRepository;
    private final VideoFileRepository videoRepository;
    private final ExportService exportService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("export-queue");
        thread.setDaemon(true);
        return thread;
    });

    public ExportTaskService(
            ExportTaskRepository taskRepository,
            VideoFileRepository videoRepository,
            ExportService exportService
    ) {
        this.taskRepository = taskRepository;
        this.videoRepository = videoRepository;
        this.exportService = exportService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingTasks() {
        List<ExportTask> pending = taskRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(ExportTaskStatus.QUEUED, ExportTaskStatus.RUNNING));
        for (ExportTask task : pending) {
            task.setStatus(ExportTaskStatus.QUEUED);
            task.setProgress(0);
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
            submit(task.getId());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public ExportTaskResponse enqueue(Long videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "视频不存在");
        }
        boolean[] created = {false};
        ExportTask task = taskRepository.findActiveByVideoId(videoId).orElseGet(() -> {
            created[0] = true;
            ExportTask newTask = new ExportTask();
            newTask.setVideoId(videoId);
            newTask.setStatus(ExportTaskStatus.QUEUED);
            newTask.setProgress(0);
            return taskRepository.save(newTask);
        });
        if (created[0]) {
            submit(task.getId());
        }
        return ExportTaskResponse.from(task);
    }

    @Transactional(readOnly = true)
    public List<ExportTaskResponse> listByVideo(Long videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "视频不存在");
        }
        return taskRepository.findByVideoIdOrderByCreatedAtDesc(videoId).stream()
                .map(ExportTaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExportTaskResponse get(Long id) {
        return taskRepository.findById(id)
                .map(ExportTaskResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "导出任务不存在"));
    }

    public BatchOperationResponse batchEnqueue(List<Long> videoIds) {
        List<BatchItemResponse> items = new ArrayList<>();
        for (Long videoId : videoIds) {
            try {
                ExportTaskResponse task = enqueue(videoId);
                items.add(BatchItemResponse.ok(videoId, "导出任务 #" + task.id() + " 已入队"));
            } catch (Exception ex) {
                items.add(BatchItemResponse.failed(videoId, readableMessage(ex)));
            }
        }
        return BatchOperationResponse.from(items);
    }

    private void submit(Long taskId) {
        executor.submit(() -> runTask(taskId));
    }

    private void runTask(Long taskId) {
        ExportTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() == ExportTaskStatus.COMPLETED) {
            return;
        }
        try {
            task.setStatus(ExportTaskStatus.RUNNING);
            task.setProgress(20);
            task.setStartedAt(Instant.now());
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);

            ExportResponse result = exportService.exportConfirmedClips(task.getVideoId());
            task.setJobId(result.jobId());
            task.setExportPath(result.exportPath());
            task.setRemovedClipCount(result.removedClipCount());
            task.setStatus(ExportTaskStatus.COMPLETED);
            task.setProgress(100);
            task.setCompletedAt(Instant.now());
            task.setErrorMessage(null);
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
        } catch (Exception ex) {
            log.warn("导出任务失败 taskId={}: {}", taskId, readableMessage(ex));
            ExportTask failed = taskRepository.findById(taskId).orElse(task);
            failed.setStatus(ExportTaskStatus.FAILED);
            failed.setProgress(100);
            failed.setErrorMessage(readableMessage(ex));
            failed.setCompletedAt(Instant.now());
            failed.setUpdatedAt(Instant.now());
            taskRepository.save(failed);
        }
    }

    private String readableMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
