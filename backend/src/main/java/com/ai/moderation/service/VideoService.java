package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;
import com.ai.moderation.dto.JobResponse;
import com.ai.moderation.dto.VideoResponse;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private final StorageProperties storageProperties;
    private final VideoFileRepository videoRepository;
    private final DetectionJobRepository jobRepository;
    private final TranscriptWordRepository wordRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository aiReviewRepository;
    private final ClipSuggestionRepository suggestionRepository;
    private final DetectionPipelineService detectionPipelineService;
    private final FfmpegService ffmpegService;

    public VideoService(
            StorageProperties storageProperties,
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            TranscriptWordRepository wordRepository,
            TranscriptSegmentRepository segmentRepository,
            TermHitRepository hitRepository,
            AiReviewRepository aiReviewRepository,
            ClipSuggestionRepository suggestionRepository,
            DetectionPipelineService detectionPipelineService,
            FfmpegService ffmpegService
    ) {
        this.storageProperties = storageProperties;
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.wordRepository = wordRepository;
        this.segmentRepository = segmentRepository;
        this.hitRepository = hitRepository;
        this.aiReviewRepository = aiReviewRepository;
        this.suggestionRepository = suggestionRepository;
        this.detectionPipelineService = detectionPipelineService;
        this.ffmpegService = ffmpegService;
    }

    @Transactional
    public List<VideoResponse> listVideos() {
        return videoRepository.findAll().stream()
                .map(this::fillMissingDuration)
                .map(VideoResponse::from)
                .toList();
    }

    @Transactional
    public VideoResponse getVideo(Long id) {
        return VideoResponse.from(fillMissingDuration(findVideo(id)));
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

    @Transactional
    public void deleteVideo(Long id) {
        VideoFile video = findVideo(id);
        List<DetectionJob> jobs = jobRepository.findByVideoIdOrderByCreatedAtDesc(id);
        boolean activeJob = jobs.stream().anyMatch(job -> job.getStatus() != JobStatus.COMPLETED && job.getStatus() != JobStatus.FAILED);
        if (activeJob) {
            throw new ApiException(HttpStatus.CONFLICT, "视频正在检测中，请等待任务结束后再删除");
        }

        List<String> exportedPaths = jobs.stream()
                .flatMap(job -> suggestionRepository.findByJobIdOrderByStartTimeAsc(job.getId()).stream())
                .map(item -> item.getExportPath())
                .filter(path -> path != null && !path.isBlank())
                .toList();
        for (DetectionJob job : jobs) {
            Long jobId = job.getId();
            List<Long> hitIds = hitRepository.findByJobIdOrderByStartTimeAsc(jobId).stream()
                    .map(item -> item.getId())
                    .filter(Objects::nonNull)
                    .toList();
            suggestionRepository.deleteByJobId(jobId);
            aiReviewRepository.deleteByHitIds(hitIds);
            hitRepository.deleteByJobId(jobId);
            wordRepository.deleteByJobId(jobId);
            segmentRepository.deleteByJobId(jobId);
        }
        jobRepository.deleteByVideoId(id);
        videoRepository.deleteById(id);
        deleteStoredFiles(video, exportedPaths);
    }

    private VideoFile findVideo(Long id) {
        return videoRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"));
    }

    private VideoFile fillMissingDuration(VideoFile video) {
        if (video.getDurationSeconds() != null && video.getDurationSeconds() > 0) {
            return video;
        }
        Double duration = ffmpegService.probeDuration(Path.of(video.getStoragePath()));
        if (duration != null && duration > 0) {
            video.setDurationSeconds(duration);
            videoRepository.save(video);
        }
        return video;
    }

    private void deleteStoredFiles(VideoFile video, List<String> exportedPaths) {
        Path storageRoot = Path.of(storageProperties.rootPath()).toAbsolutePath().normalize();
        Path videoPath = Path.of(video.getStoragePath()).toAbsolutePath().normalize();
        Path uploadDir = videoPath.getParent();
        if (uploadDir == null || !uploadDir.startsWith(storageRoot)) {
            return;
        }
        try {
            deleteIfInsideStorage(video.getSubtitlePath(), storageRoot);
            for (String exportPath : exportedPaths) {
                deleteIfInsideStorage(exportPath, storageRoot);
                deleteEmptyParents(Path.of(exportPath).toAbsolutePath().normalize(), storageRoot);
            }
            deleteDirectory(uploadDir);
        } catch (IOException ex) {
            // Keep the record deletion durable even if Windows still has a preview/export file open.
            log.warn("Failed to delete stored files for video {}", video.getId(), ex);
        }
    }

    private void deleteIfInsideStorage(String rawPath, Path storageRoot) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (path.startsWith(storageRoot)) {
            Files.deleteIfExists(path);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            List<Path> sorted = paths.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path path : sorted) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void deleteEmptyParents(Path path, Path storageRoot) throws IOException {
        Path parent = path.getParent();
        while (parent != null && parent.startsWith(storageRoot) && !parent.equals(storageRoot)) {
            if (!Files.exists(parent)) {
                parent = parent.getParent();
                continue;
            }
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(parent);
            parent = parent.getParent();
        }
    }

    private String sanitize(String name) {
        String fallback = "upload.bin";
        String value = name == null || name.isBlank() ? fallback : name;
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
