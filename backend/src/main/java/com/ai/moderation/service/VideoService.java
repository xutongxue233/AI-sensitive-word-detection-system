package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.TermHit;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private final StorageProperties storageProperties;
    private final VideoFileRepository videoRepository;
    private final DetectionJobRepository jobRepository;
    private final DetectionPipelineService detectionPipelineService;
    private final FfmpegService ffmpegService;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;
    private final ClipSuggestionRepository clipSuggestionRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptWordRepository wordRepository;

    public VideoService(
            StorageProperties storageProperties,
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            DetectionPipelineService detectionPipelineService,
            FfmpegService ffmpegService,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository,
            ClipSuggestionRepository clipSuggestionRepository,
            TranscriptSegmentRepository segmentRepository,
            TranscriptWordRepository wordRepository
    ) {
        this.storageProperties = storageProperties;
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.detectionPipelineService = detectionPipelineService;
        this.ffmpegService = ffmpegService;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
        this.clipSuggestionRepository = clipSuggestionRepository;
        this.segmentRepository = segmentRepository;
        this.wordRepository = wordRepository;
    }

    @Transactional(readOnly = true)
    public List<VideoResponse> listVideos() {
        return videoRepository.findAllByOrderByCreatedAtDesc().stream().map(VideoResponse::from).toList();
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
            String storedFilename = uuid + extension(video.getOriginalFilename());
            Path videoPath = dayDir.resolve(storedFilename);
            video.transferTo(videoPath);

            String subtitlePath = null;
            if (subtitle != null && !subtitle.isEmpty()) {
                String subtitleName = uuid + "-subtitle" + extension(subtitle.getOriginalFilename());
                Path path = dayDir.resolve(subtitleName);
                subtitle.transferTo(path);
                subtitlePath = path.toString();
            }

            VideoFile file = new VideoFile();
            file.setOriginalFilename(video.getOriginalFilename());
            file.setStoredFilename(storedFilename);
            file.setStoragePath(videoPath.toString());
            file.setSubtitlePath(subtitlePath);
            file.setSizeBytes(video.getSize());
            file.setContentType(video.getContentType());
            int[] resolution = ffmpegService.probeResolution(videoPath);
            if (resolution != null) {
                file.setWidth(resolution[0]);
                file.setHeight(resolution[1]);
            }
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

    /**
     * 删除视频及其全部关联数据:逐个检测任务清理 term_hits/ai_reviews/clip_suggestions
     * 与转写 segments/words,再删任务与视频记录,最后清理磁盘上的视频/字幕文件。
     */
    @Transactional
    public void deleteVideo(Long id) {
        VideoFile video = findVideo(id);
        List<DetectionJob> jobs = jobRepository.findByVideoIdOrderByCreatedAtDesc(id);
        for (DetectionJob job : jobs) {
            List<TermHit> hits = hitRepository.findByJobIdOrderByStartTimeAsc(job.getId());
            // 删除顺序须满足外键(引用方先删): ai_reviews、clip_suggestions 都引用 term_hits,
            // term_hits、transcript_words 都引用 transcript_segments
            reviewRepository.deleteByHitIds(hits.stream().map(TermHit::getId).toList());
            clipSuggestionRepository.deleteByJobId(job.getId());
            hitRepository.deleteByJobId(job.getId());
            wordRepository.deleteByJobId(job.getId());
            segmentRepository.deleteByJobId(job.getId());
        }
        jobRepository.deleteByVideoId(id);
        videoRepository.deleteById(id);
        deleteStorageFiles(video);
    }

    /**
     * 删除磁盘文件,优先删整个 uuid 目录(上传时视频与字幕同放该目录);
     * 文件缺失或清理失败只记录日志,不影响数据库删除已提交的结果。
     */
    private void deleteStorageFiles(VideoFile video) {
        try {
            Path videoPath = video.getStoragePath() == null ? null : Path.of(video.getStoragePath());
            Path uuidDir = videoPath == null ? null : videoPath.getParent();
            if (uuidDir != null && Files.isDirectory(uuidDir) && isInsideStorageRoot(uuidDir)) {
                try (Stream<Path> walk = Files.walk(uuidDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            log.warn("删除文件失败 {}: {}", path, ex.getMessage());
                        }
                    });
                }
                return;
            }
            // 目录结构不符合预期时,退而删除已知的单个文件
            deleteIfPresent(video.getStoragePath());
            deleteIfPresent(video.getSubtitlePath());
        } catch (IOException ex) {
            log.warn("清理视频存储目录失败 videoId={}: {}", video.getId(), ex.getMessage());
        }
    }

    private boolean isInsideStorageRoot(Path dir) {
        Path root = Path.of(storageProperties.rootPath()).toAbsolutePath().normalize();
        return dir.toAbsolutePath().normalize().startsWith(root);
    }

    private void deleteIfPresent(String path) throws IOException {
        if (path != null && !path.isBlank()) {
            Files.deleteIfExists(Path.of(path));
        }
    }

    private VideoFile findVideo(Long id) {
        return videoRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"));
    }

    /** 从原始文件名提取小写扩展名(含点,如 ".mp4");无合法扩展名时返回空串。存储文件名只用 UUID,不含原始名。 */
    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot);
        return ext.matches("\\.[A-Za-z0-9]{1,10}") ? ext.toLowerCase(Locale.ROOT) : "";
    }
}
