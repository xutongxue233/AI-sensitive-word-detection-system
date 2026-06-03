package com.ai.moderation.controller;

import com.ai.moderation.dto.ExportResponse;
import com.ai.moderation.dto.JobResponse;
import com.ai.moderation.dto.VideoResponse;
import com.ai.moderation.service.ExportService;
import com.ai.moderation.service.VideoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

