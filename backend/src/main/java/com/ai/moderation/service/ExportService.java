package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.*;
import com.ai.moderation.dto.ExportResponse;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExportService {
    private final StorageProperties storageProperties;
    private final VideoFileRepository videoRepository;
    private final DetectionJobRepository jobRepository;
    private final ClipSuggestionRepository suggestionRepository;
    private final TermHitRepository hitRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final FfmpegService ffmpegService;
    private final SettingsService settingsService;

    public ExportService(
            StorageProperties storageProperties,
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            ClipSuggestionRepository suggestionRepository,
            TermHitRepository hitRepository,
            TranscriptSegmentRepository segmentRepository,
            FfmpegService ffmpegService,
            SettingsService settingsService
    ) {
        this.storageProperties = storageProperties;
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.suggestionRepository = suggestionRepository;
        this.hitRepository = hitRepository;
        this.segmentRepository = segmentRepository;
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
        Map<Long, TermHit> hitsById = hitRepository.findByJobIdOrderByStartTimeAsc(job.getId()).stream()
                .collect(Collectors.toMap(TermHit::getId, Function.identity(), (a, b) -> a));
        Map<Long, TranscriptSegment> segmentsById = segmentRepository.findByJobIdOrderBySequenceNoAsc(job.getId()).stream()
                .collect(Collectors.toMap(TranscriptSegment::getId, Function.identity(), (a, b) -> a));

        List<FfmpegService.TimeRange> audioRanges = suggestions.stream()
                .filter(item -> isAudioSuggestion(item, hitsById))
                .sorted(Comparator.comparingDouble(ClipSuggestion::getStartTime))
                .map(item -> new FfmpegService.TimeRange(item.getStartTime(), item.getEndTime()))
                .toList();
        List<FfmpegService.SubtitleMaskRange> subtitleRanges = suggestions.stream()
                .filter(item -> !isAudioSuggestion(item, hitsById))
                .sorted(Comparator.comparingDouble(ClipSuggestion::getStartTime))
                .map(item -> toSubtitleMaskRange(item, hitsById, segmentsById))
                .filter(Objects::nonNull)
                .toList();
        Path outputDir = Path.of(storageProperties.rootPath(), "exports", "video-" + videoId, "job-" + job.getId());
        Path source = ffmpegService.exportWithSubtitleBlur(Path.of(video.getStoragePath()), outputDir, subtitleRanges);
        Path output = audioRanges.isEmpty()
                ? source
                : ffmpegService.exportWithoutClips(
                        source, outputDir, audioRanges, durationValue, settingsService.currentClip().preciseExport());
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

    private boolean isAudioSuggestion(ClipSuggestion suggestion, Map<Long, TermHit> hitsById) {
        TermHit hit = hitsById.get(suggestion.getHitId());
        return hit == null || hit.getSource() == null || hit.getSource() == TranscriptSource.AUDIO;
    }

    private FfmpegService.SubtitleMaskRange toSubtitleMaskRange(
            ClipSuggestion suggestion,
            Map<Long, TermHit> hitsById,
            Map<Long, TranscriptSegment> segmentsById
    ) {
        TermHit hit = hitsById.get(suggestion.getHitId());
        if (hit == null) {
            return null;
        }
        TranscriptSegment segment = segmentsById.get(hit.getSegmentId());
        double x = segment == null || segment.getBboxX() == null ? 0.08 : segment.getBboxX();
        double y = segment == null || segment.getBboxY() == null ? 0.74 : segment.getBboxY();
        double width = segment == null || segment.getBboxWidth() == null ? 0.84 : segment.getBboxWidth();
        double height = segment == null || segment.getBboxHeight() == null ? 0.16 : segment.getBboxHeight();
        return new FfmpegService.SubtitleMaskRange(suggestion.getStartTime(), suggestion.getEndTime(), x, y, width, height);
    }
}
