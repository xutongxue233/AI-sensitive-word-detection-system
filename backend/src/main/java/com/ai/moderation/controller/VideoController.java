package com.ai.moderation.controller;

import com.ai.moderation.dto.ExportResponse;
import com.ai.moderation.dto.JobResponse;
import com.ai.moderation.dto.VideoResponse;
import com.ai.moderation.service.ExportService;
import com.ai.moderation.service.VideoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 视频与检测任务生命周期的 REST 入口,前缀 {@code /api/v1/videos}。
 *
 * <p>聚合 {@link VideoService}(视频上传/列表/删除、任务列表、触发检测任务)
 * 与 {@link ExportService}(把已确认的剪辑/去字幕建议导出成片)。
 *
 * <p>位于检测管线的入口端:{@code POST /videos/{id}/jobs} 经
 * {@link VideoService#createDetectionJob} 触发 {@code DetectionPipelineService} 的异步处理;
 * 视频/导出文件的二进制流式播放由 {@link VideoContentController} 单独承担。
 */
@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {
    private final VideoService videoService;
    private final ExportService exportService;

    public VideoController(VideoService videoService, ExportService exportService) {
        this.videoService = videoService;
        this.exportService = exportService;
    }

    @GetMapping
    public List<VideoResponse> listVideos() {
        return videoService.listVideos();
    }

    @GetMapping("/{id}")
    public VideoResponse getVideo(@PathVariable Long id) {
        return videoService.getVideo(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVideo(@PathVariable Long id) {
        videoService.deleteVideo(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VideoResponse upload(
            @RequestPart("video") MultipartFile video,
            @RequestPart(value = "subtitle", required = false) MultipartFile subtitle
    ) {
        return videoService.upload(video, subtitle);
    }

    @GetMapping("/{id}/jobs")
    public List<JobResponse> listJobs(@PathVariable Long id) {
        return videoService.listJobs(id);
    }

    @PostMapping("/{id}/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(@PathVariable Long id) {
        return videoService.createDetectionJob(id);
    }

    @PostMapping("/{id}/exports")
    public ExportResponse export(@PathVariable Long id) {
        return exportService.exportConfirmedClips(id);
    }
}

