package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.*;
import com.ai.moderation.dto.ExportResponse;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class ExportService {
    private final StorageProperties storageProperties;
    private final VideoFileRepository videoRepository;
    private final DetectionJobRepository jobRepository;
    private final ClipSuggestionRepository suggestionRepository;
    private final FfmpegService ffmpegService;
    private final SettingsService settingsService;

    public ExportService(
            StorageProperties storageProperties,
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            ClipSuggestionRepository suggestionRepository,
            FfmpegService ffmpegService,
            SettingsService settingsService
    ) {
        this.storageProperties = storageProperties;
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.suggestionRepository = suggestionRepository;
        this.ffmpegService = ffmpegService;
        this.settingsService = settingsService;
    }

    @Transactional
    public ExportResponse exportConfirmedClips(Long videoId) {
        VideoFile video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"));
        DetectionJob job = jobRepository.findByVideoIdOrderByCreatedAtDesc(videoId)
                .stream()
                .filter(item -> item.getStatus() == JobStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "没有已完成的检测任务"));
        List<ClipSuggestion> suggestions = suggestionRepository.findByJobIdAndStatusOrderByStartTimeAsc(job.getId(), ClipStatus.CONFIRMED);
        if (suggestions.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "没有已确认的剪辑片段");
        }
        Double durationValue = video.getDurationSeconds() == null
                ? ffmpegService.probeDuration(Path.of(video.getStoragePath()))
                : video.getDurationSeconds();
        if (durationValue == null || durationValue <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法获取视频时长，不能导出");
        }
        List<FfmpegService.TimeRange> ranges = suggestions.stream()
                .sorted(Comparator.comparingDouble(ClipSuggestion::getStartTime))
                .map(item -> new FfmpegService.TimeRange(item.getStartTime(), item.getEndTime()))
                .toList();
        Path outputDir = Path.of(storageProperties.rootPath(), "exports", "video-" + videoId, "job-" + job.getId());
        Path output = ffmpegService.exportWithoutClips(
                Path.of(video.getStoragePath()), outputDir, ranges, durationValue, settingsService.currentClip().preciseExport());
        suggestions.forEach(suggestion -> {
            suggestion.setStatus(ClipStatus.EXPORTED);
            suggestion.setExportPath(output.toString());
            suggestion.setUpdatedAt(Instant.now());
        });
        suggestionRepository.saveAll(suggestions);
        video.setStatus(VideoStatus.EXPORTED);
        videoRepository.save(video);
        return new ExportResponse(videoId, job.getId(), output.toString(), suggestions.size());
    }
}
