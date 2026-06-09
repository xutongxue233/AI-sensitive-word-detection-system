package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.dto.ClipSuggestionResponse;
import com.ai.moderation.dto.BatchItemResponse;
import com.ai.moderation.dto.BatchOperationResponse;
import com.ai.moderation.dto.ClipSuggestionBatchUpdateRequest;
import com.ai.moderation.dto.ClipSuggestionUpdateRequest;
import com.ai.moderation.dto.ManualClipSuggestionRequest;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 剪辑建议服务:SUGGESTING_CLIPS 阶段把 {@link ReviewStatus#VIOLATION} 命中
 * 按留白(padding)生成 {@link ClipSuggestion},并支持前端对单条建议的人工微调。
 *
 * <p>只有通过 AI 复核且达置信阈值的违规命中才会生成建议(SAFE/未达阈值的不自动剪),
 * 保证"可确认、可调整"的剪辑列表只包含真正需要处理的时段。
 */
@Service
public class ClipSuggestionService {
    private final ClipSuggestionRepository suggestionRepository;
    private final TermHitRepository hitRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final DetectionJobRepository jobRepository;
    private final SettingsService settingsService;

    public ClipSuggestionService(
            ClipSuggestionRepository suggestionRepository,
            TermHitRepository hitRepository,
            TranscriptSegmentRepository segmentRepository,
            DetectionJobRepository jobRepository,
            SettingsService settingsService
    ) {
        this.suggestionRepository = suggestionRepository;
        this.hitRepository = hitRepository;
        this.segmentRepository = segmentRepository;
        this.jobRepository = jobRepository;
        this.settingsService = settingsService;
    }

    /**
     * 使用运行时设置的剪辑留白(app.clip.padding-seconds，默认 0.2s，可在前台调整)生成剪辑建议。
     * 收紧 padding 是避免"切掉过多时间轴"的核心:命中时间戳本身已是 Whisper 词级边界。
     */
    @Transactional
    public List<ClipSuggestionResponse> createSuggestions(Long jobId) {
        return createSuggestions(jobId, settingsService.currentClip().paddingSeconds());
    }

    /**
     * 按指定 padding 重建某任务的剪辑建议(重载版,供管线与重算复用)。
     *
     * <p>流程:先删该 job 旧建议(幂等重建)→ 取所有 {@link ReviewStatus#VIOLATION} 命中
     * → 按命中时段向两端各扩 padding 生成建议,状态置 {@link ClipStatus#PENDING}。
     * startTime 用 {@code Math.max(0, ...)} 防止扩留白后越过视频起点变成负值。
     *
     * @param jobId          检测任务 id
     * @param paddingSeconds 命中两端留白秒数(负值按 0 处理)
     * @return 新生成的剪辑建议响应
     */
    @Transactional
    public List<ClipSuggestionResponse> createSuggestions(Long jobId, double paddingSeconds) {
        DetectionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "检测任务不存在"));
        double padding = Math.max(0, paddingSeconds);
        suggestionRepository.deleteByJobId(jobId);
        List<TermHit> hits = hitRepository.findByJobIdAndReviewStatusOrderByStartTimeAsc(jobId, ReviewStatus.VIOLATION);
        List<ClipSuggestion> suggestions = hits.stream().map(hit -> {
            SuggestionRange range = suggestionRange(hit, padding);
            ClipSuggestion suggestion = new ClipSuggestion();
            suggestion.setJobId(job.getId());
            suggestion.setHitId(hit.getId());
            suggestion.setPaddingSeconds(padding);
            suggestion.setStartTime(range.start());
            suggestion.setEndTime(range.end());
            suggestion.setStatus(ClipStatus.PENDING);
            return suggestion;
        }).toList();
        return suggestionRepository.saveAll(suggestions).stream().map(this::toResponse).toList();
    }

    /** 列出某任务全部剪辑建议(按起始时间升序),供前端审核列表展示。 */
    @Transactional(readOnly = true)
    public List<ClipSuggestionResponse> listSuggestions(Long jobId) {
        return suggestionRepository.findByJobIdOrderByStartTimeAsc(jobId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 从人工选中的转写文本创建一条剪辑建议。
     *
     * <p>导出链路依赖 {@link TermHit#getSource()} 和 {@link TermHit#getSegmentId()} 判断处理方式,
     * 因此这里先创建一条人工命中,再创建剪辑建议。人工命中标记为 {@link ReviewStatus#VIOLATION},
     * 这样后续重新生成剪辑建议时不会丢失这类人工补充项;建议本身仍为待确认,导出前需审核员确认。
     */
    @Transactional
    public ClipSuggestionResponse createManualSuggestion(Long jobId, ManualClipSuggestionRequest request) {
        DetectionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "检测任务不存在"));
        TranscriptSegment segment = segmentRepository.findById(request.segmentId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "转写句段不存在"));
        if (!job.getId().equals(segment.getJobId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "转写句段不属于当前检测任务");
        }

        String matchedText = normalizeManualText(request.matchedText());
        double start = clamp(request.startTime(), segment.getStartTime(), segment.getEndTime());
        double end = clamp(request.endTime(), segment.getStartTime(), segment.getEndTime());
        if (end <= start) {
            end = Math.min(segment.getEndTime(), start + 0.1);
        }
        if (end <= start) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "选中文本时间范围无效");
        }

        TermHit hit = new TermHit();
        hit.setJobId(job.getId());
        hit.setSegmentId(segment.getId());
        hit.setMatchedText(matchedText);
        hit.setCategory("人工添加");
        hit.setSeverity(Severity.MEDIUM);
        hit.setRuleSource(MatchType.EXACT);
        hit.setSource(segment.getSource() == null ? TranscriptSource.AUDIO : segment.getSource());
        hit.setStartTime(start);
        hit.setEndTime(end);
        hit.setContextText(segment.getText());
        hit.setReviewStatus(ReviewStatus.VIOLATION);
        hit.setAiConfidence(1.0);
        hitRepository.save(hit);

        double padding = Math.max(0, settingsService.currentClip().paddingSeconds());
        SuggestionRange range = suggestionRange(hit, segment, padding);
        ClipSuggestion suggestion = new ClipSuggestion();
        suggestion.setJobId(job.getId());
        suggestion.setHitId(hit.getId());
        suggestion.setPaddingSeconds(padding);
        suggestion.setStartTime(range.start());
        suggestion.setEndTime(range.end());
        suggestion.setStatus(ClipStatus.PENDING);
        return toResponse(suggestionRepository.save(suggestion));
    }

    /**
     * 人工微调单条剪辑建议的起止时间与状态(确认/调整时调用)。
     * startTime/endTime 仅在请求传入时更新,status 总是覆盖为请求值。
     *
     * @param id      剪辑建议 id
     * @param request 待更新的起止时间(可空,空则不改)与目标状态
     * @return 更新后的建议响应
     */
    @Transactional
    public ClipSuggestionResponse updateSuggestion(Long id, ClipSuggestionUpdateRequest request) {
        ClipSuggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "剪辑建议不存在"));
        if (request.startTime() != null) {
            suggestion.setStartTime(Math.max(0, request.startTime()));
        }
        if (request.endTime() != null) {
            // +0.1 守卫:保证片段时长 ≥ 0.1s,防止前端把 end 调到 start 之前或与之重合,产生零长/反向片段。
            suggestion.setEndTime(Math.max(suggestion.getStartTime() + 0.1, request.endTime()));
        }
        suggestion.setStatus(request.status());
        suggestion.setUpdatedAt(Instant.now());
        return toResponse(suggestionRepository.save(suggestion));
    }

    @Transactional
    public BatchOperationResponse batchUpdateSuggestions(ClipSuggestionBatchUpdateRequest request) {
        List<BatchItemResponse> items = request.items().stream().map(item -> {
            try {
                updateSuggestion(item.id(), item.toUpdateRequest());
                return BatchItemResponse.ok(item.id(), "已更新");
            } catch (Exception ex) {
                return BatchItemResponse.failed(item.id(), readableMessage(ex));
            }
        }).toList();
        return BatchOperationResponse.from(items);
    }

    /**
     * 把剪辑建议补齐其命中信息(命中文本、AI 置信度、来源)后转为响应。
     * 命中缺失或 source 未标注时按 {@link TranscriptSource#AUDIO} 兜底(前端默认按音频删片段呈现)。
     */
    private ClipSuggestionResponse toResponse(ClipSuggestion suggestion) {
        TermHit hit = hitRepository.findById(suggestion.getHitId()).orElse(null);
        String matchedText = hit == null ? "-" : hit.getMatchedText();
        Double aiConfidence = hit == null ? null : hit.getAiConfidence();
        TranscriptSource source = hit == null || hit.getSource() == null ? TranscriptSource.AUDIO : hit.getSource();
        TranscriptSegment segment = hit == null || hit.getSegmentId() == null
                ? null
                : segmentRepository.findById(hit.getSegmentId()).orElse(null);
        Double segmentStartTime = segment == null ? null : segment.getStartTime();
        Double segmentEndTime = segment == null ? null : segment.getEndTime();
        return ClipSuggestionResponse.from(suggestion, matchedText, source, aiConfidence, segmentStartTime, segmentEndTime);
    }

    private SuggestionRange suggestionRange(TermHit hit, double padding) {
        TranscriptSegment segment = hit.getSegmentId() == null
                ? null
                : segmentRepository.findById(hit.getSegmentId()).orElse(null);
        return suggestionRange(hit, segment, padding);
    }

    /**
     * 字幕类命中默认扩展到整条字幕句段,避免只遮住命中的几个字;音频命中仍使用词级时间。
     * 前端可继续人工调整 start/end,导出阶段会严格使用建议上的最终时间。
     */
    private SuggestionRange suggestionRange(TermHit hit, TranscriptSegment segment, double padding) {
        double start = hit.getStartTime();
        double end = hit.getEndTime();
        if (isSubtitleSource(hit.getSource()) && segment != null) {
            start = segment.getStartTime();
            end = segment.getEndTime();
        }
        return new SuggestionRange(Math.max(0, start - padding), Math.max(start + 0.1, end + padding));
    }

    private boolean isSubtitleSource(TranscriptSource source) {
        return source == TranscriptSource.VIDEO_SUBTITLE || source == TranscriptSource.SUBTITLE_FILE;
    }

    private String normalizeManualText(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "选中文本不能为空");
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "时间范围无效");
        }
        return Math.max(min, Math.min(max, value));
    }

    private String readableMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record SuggestionRange(double start, double end) {
    }
}
