package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;
import com.ai.moderation.dto.JobResponse;
import com.ai.moderation.dto.VideoResponse;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class VideoService {
    private final StorageProperties storageProperties;
    private final VideoFileRepository videoRepository;
    private final DetectionJobRepository jobRepository;
    private final DetectionPipelineService detectionPipelineService;
    private final FfmpegService ffmpegService;

    public VideoService(
            StorageProperties storageProperties,
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            DetectionPipelineService detectionPipelineService,
            FfmpegService ffmpegService
    ) {
        this.storageProperties = storageProperties;
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.detectionPipelineService = detectionPipelineService;
        this.ffmpegService = ffmpegService;
    }

    @Transactional(readOnly = true)
    public List<VideoResponse> listVideos() {
        return videoRepository.findAll().stream().map(VideoResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public VideoResponse getVideo(Long id) {
        return VideoResponse.from(findVideo(id));
    }

    @Transactional
    public VideoResponse upload(MultipartFile video, MultipartFile subtitle) {
        if (video == null || video.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请上传视频文件");
        }
        try {
            String uuid = UUID.randomUUID().toString();
            Path dayDir = Path.of(storageProperties.rootPath(), "videos", LocalDate.now().toString(), uuid);
            Files.createDirectories(dayDir);
            String storedFilename = uuid + "-" + sanitize(video.getOriginalFilename());
            Path videoPath = dayDir.resolve(storedFilename);
            video.transferTo(videoPath);

            String subtitlePath = null;
            if (subtitle != null && !subtitle.isEmpty()) {
                String subtitleName = uuid + "-" + sanitize(subtitle.getOriginalFilename());
                Path path = dayDir.resolve(subtitleName);
                subtitle.transferTo(path);
                subtitlePath = path.toString();
            }

            VideoFile file = new VideoFile();
            file.setOriginalFilename(video.getOriginalFilename());
            file.setStoredFilename(storedFilename);
            file.setStoragePath(videoPath.toString());
            file.setSubtitlePath(subtitlePath);
            file.setDurationSeconds(ffmpegService.probeDuration(videoPath));
            return VideoResponse.from(videoRepository.save(file));
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "保存视频失败: " + ex.getMessage());
        }
    }

    public JobResponse createDetectionJob(Long videoId) {
        VideoFile video = findVideo(videoId);
        video.setStatus(VideoStatus.DETECTING);
        videoRepository.save(video);
        DetectionJob job = new DetectionJob();
        job.setVideoId(video.getId());
        job.setStatus(JobStatus.QUEUED);
        job.setProgress(0);
        DetectionJob saved = jobRepository.save(job);
        detectionPipelineService.processAsync(saved.getId());
        return JobResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<JobResponse> listJobs(Long videoId) {
        return jobRepository.findByVideoIdOrderByCreatedAtDesc(videoId).stream().map(JobResponse::from).toList();
    }

    private VideoFile findVideo(Long id) {
        return videoRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"));
    }

    private String sanitize(String name) {
        String fallback = "upload.bin";
        String value = name == null || name.isBlank() ? fallback : name;
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
