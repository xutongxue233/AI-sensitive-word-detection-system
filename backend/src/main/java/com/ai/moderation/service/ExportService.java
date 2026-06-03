package com.ai.moderation.service;

import com.ai.moderation.asr.SubtitleRemovalClient;
import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.config.SubtitleRemovalProperties;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private final SubtitleRemovalClient subtitleRemovalClient;
    private final SubtitleRemovalProperties subtitleRemovalProperties;

    public ExportService(
            StorageProperties storageProperties,
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            ClipSuggestionRepository suggestionRepository,
            TermHitRepository hitRepository,
            TranscriptSegmentRepository segmentRepository,
            FfmpegService ffmpegService,
            SettingsService settingsService,
            SubtitleRemovalClient subtitleRemovalClient,
            SubtitleRemovalProperties subtitleRemovalProperties
    ) {
        this.storageProperties = storageProperties;
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.suggestionRepository = suggestionRepository;
        this.hitRepository = hitRepository;
        this.segmentRepository = segmentRepository;
        this.ffmpegService = ffmpegService;
        this.settingsService = settingsService;
        this.subtitleRemovalClient = subtitleRemovalClient;
        this.subtitleRemovalProperties = subtitleRemovalProperties;
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
        Path videoPath = Path.of(video.getStoragePath());
        Path source;
        if (!subtitleRanges.isEmpty() && subtitleRemovalProperties.useVsr()) {
            // 画面命中走 VSR 切片去字幕;失败回退 delogo,保证导出不被 VSR 阻断
            Path removed = exportSubtitlesWithVsr(videoPath, outputDir, subtitleRanges, durationValue);
            source = removed != null ? removed : ffmpegService.exportWithSubtitleBlur(videoPath, outputDir, subtitleRanges);
        } else {
            source = ffmpegService.exportWithSubtitleBlur(videoPath, outputDir, subtitleRanges);
        }
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

    /**
     * 用 VSR 按违规时间段切片去字幕:keep 段保留原样,违规段切出送 vsr-service inpainting 再拼回,
     * 只去除命中字幕的像素区域。任一步失败返回 null,由调用方回退 delogo。
     */
    private Path exportSubtitlesWithVsr(Path input, Path outputDir,
                                        List<FfmpegService.SubtitleMaskRange> ranges, double duration) {
        int[] resolution = ffmpegService.probeResolution(input);
        if (resolution == null) {
            return null;
        }
        int width = resolution[0];
        int height = resolution[1];
        List<VsrSegment> segments = mergeRanges(ranges);
        if (segments.isEmpty()) {
            return input;
        }
        Path workDir = outputDir.resolve("vsr-work-" + System.currentTimeMillis());
        try {
            Files.createDirectories(workDir);
            List<Path> parts = new ArrayList<>();
            double cursor = 0;
            int idx = 0;
            for (VsrSegment seg : segments) {
                double segStart = Math.max(0, seg.start());
                double segEnd = Math.min(duration, seg.end());
                if (segEnd <= segStart) {
                    continue;
                }
                if (segStart > cursor + 0.04) {
                    parts.add(ffmpegService.cutSegment(input, cursor, segStart, workDir.resolve("keep-" + idx + ".mp4")));
                }
                Path clip = ffmpegService.cutSegment(input, segStart, segEnd, workDir.resolve("clip-" + idx + ".mp4"));
                List<int[]> areas = toPixelAreas(seg.boxes(), width, height);
                Path cleaned = workDir.resolve("clean-" + idx + ".mp4");
                if (!subtitleRemovalClient.removeSubtitle(clip, areas, cleaned)) {
                    return null;
                }
                parts.add(ffmpegService.reencodeStandard(cleaned, workDir.resolve("part-" + idx + ".mp4")));
                cursor = segEnd;
                idx++;
            }
            if (cursor < duration - 0.04) {
                parts.add(ffmpegService.cutSegment(input, cursor, duration, workDir.resolve("keep-tail.mp4")));
            }
            if (parts.isEmpty()) {
                return input;
            }
            Path output = outputDir.resolve("subtitle-vsr-" + System.currentTimeMillis() + ".mp4");
            return ffmpegService.concatSegments(parts, output);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 按时间排序,把重叠或相邻(间隔<0.3s)的画面命中段合并,聚合各段的字幕框。 */
    private List<VsrSegment> mergeRanges(List<FfmpegService.SubtitleMaskRange> ranges) {
        List<FfmpegService.SubtitleMaskRange> sorted = ranges.stream()
                .sorted(Comparator.comparingDouble(FfmpegService.SubtitleMaskRange::start))
                .toList();
        List<VsrSegment> merged = new ArrayList<>();
        for (FfmpegService.SubtitleMaskRange range : sorted) {
            if (!merged.isEmpty() && range.start() <= merged.get(merged.size() - 1).end() + 0.3) {
                VsrSegment last = merged.remove(merged.size() - 1);
                last.boxes().add(range);
                merged.add(new VsrSegment(last.start(), Math.max(last.end(), range.end()), last.boxes()));
            } else {
                List<FfmpegService.SubtitleMaskRange> boxes = new ArrayList<>();
                boxes.add(range);
                merged.add(new VsrSegment(range.start(), range.end(), boxes));
            }
        }
        return merged;
    }

    /** 归一化字幕框转 VSR 的整数像素区域 [ymin,ymax,xmin,xmax]。 */
    private List<int[]> toPixelAreas(List<FfmpegService.SubtitleMaskRange> boxes, int width, int height) {
        List<int[]> areas = new ArrayList<>();
        for (FfmpegService.SubtitleMaskRange box : boxes) {
            int ymin = Math.max(0, Math.min(height - 1, (int) Math.round(box.y() * height)));
            int ymax = Math.max(ymin + 1, Math.min(height, (int) Math.round((box.y() + box.height()) * height)));
            int xmin = Math.max(0, Math.min(width - 1, (int) Math.round(box.x() * width)));
            int xmax = Math.max(xmin + 1, Math.min(width, (int) Math.round((box.x() + box.width()) * width)));
            areas.add(new int[]{ymin, ymax, xmin, xmax});
        }
        return areas;
    }

    private record VsrSegment(double start, double end, List<FfmpegService.SubtitleMaskRange> boxes) {
    }
}
