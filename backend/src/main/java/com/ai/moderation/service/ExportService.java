package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;
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

/**
 * 导出编排服务:把已确认的剪辑建议落地为最终视频文件。
 *
 * <p>按命中来源 {@link TranscriptSource} 把已确认命中分流到两条 FFmpeg 路径:
 * <ul>
 *   <li>{@link TranscriptSource#AUDIO} 音频命中 → {@link FfmpegService#exportWithoutClips}
 *       删除违规时间片段、concat 保留段;</li>
 *   <li>{@link TranscriptSource#VIDEO_SUBTITLE} 画面字幕命中 → {@link FfmpegService#exportWithSubtitleBlur}
 *       delogo 邻域插值去字幕 + 高斯柔化 + 边缘羽化。</li>
 * </ul>
 *
 * <p>导出顺序刻意为<b>先擦字幕再删音频片段</b>:擦字幕在完整时间轴上按 segment 时段定位,
 * 若先删片段会令后续 delogo 的时间戳错位。导出完成后回写视频状态为 {@link VideoStatus#EXPORTED}。
 */
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

    /**
     * 导出某视频已确认的剪辑片段为最终成片。
     *
     * <p>流程:取该视频最近一次 {@link JobStatus#COMPLETED} 任务下、状态为
     * {@link ClipStatus#CONFIRMED} 的建议;按命中来源拆成音频时段与字幕遮罩时段;
     * 先 delogo 去字幕得到中间产物,再在其上删除音频片段;最后把所有建议回写为
     * {@link ClipStatus#EXPORTED} 并标记视频 {@link VideoStatus#EXPORTED}。
     *
     * @param videoId 待导出的视频 id
     * @return 导出结果(输出路径与导出片段数)
     */
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
        // 画面字幕命中统一走 delogo(邻域插值去字幕 + 高斯柔化 + 边缘羽化);无命中时方法内直接返回原视频
        Path source = ffmpegService.exportWithSubtitleBlur(videoPath, outputDir, subtitleRanges);
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

    /**
     * 判断该剪辑建议对应的命中是否走音频删片段路径。
     * 命中缺失或 source 未标注时保守按音频处理(删片段是更安全的兜底)。
     */
    private boolean isAudioSuggestion(ClipSuggestion suggestion, Map<Long, TermHit> hitsById) {
        TermHit hit = hitsById.get(suggestion.getHitId());
        return hit == null || hit.getSource() == null || hit.getSource() == TranscriptSource.AUDIO;
    }

    /**
     * 把画面字幕命中转换为 FFmpeg delogo 的遮罩时段+归一化矩形区域;命中缺失则返回 null 跳过。
     */
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
        // 默认 bbox(x0.08/y0.74/w0.84/h0.16)是 OCR 未写入字幕框时的兜底:
        // 取画面底部居中的典型字幕区(归一化 0~1),覆盖绝大多数硬字幕位置。
        double x = segment == null || segment.getBboxX() == null ? 0.08 : segment.getBboxX();
        double y = segment == null || segment.getBboxY() == null ? 0.74 : segment.getBboxY();
        double width = segment == null || segment.getBboxWidth() == null ? 0.84 : segment.getBboxWidth();
        double height = segment == null || segment.getBboxHeight() == null ? 0.16 : segment.getBboxHeight();
        // 擦除时段用字幕整条显示时长(segment start~end),而非命中词的零点几秒,
        // 使擦除连续覆盖整条字幕、不闪烁;segment 缺失时回退到命中词时段。
        double maskStart = segment == null ? suggestion.getStartTime() : segment.getStartTime();
        double maskEnd = segment == null ? suggestion.getEndTime() : segment.getEndTime();
        return new FfmpegService.SubtitleMaskRange(maskStart, maskEnd, x, y, width, height);
    }
}
