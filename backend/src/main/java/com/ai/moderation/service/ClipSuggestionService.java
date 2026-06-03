package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.*;
import com.ai.moderation.dto.ClipSuggestionResponse;
import com.ai.moderation.dto.ClipSuggestionUpdateRequest;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ClipSuggestionService {
    private final ClipSuggestionRepository suggestionRepository;
    private final TermHitRepository hitRepository;
    private final DetectionJobRepository jobRepository;
    private final SettingsService settingsService;

    public ClipSuggestionService(
            ClipSuggestionRepository suggestionRepository,
            TermHitRepository hitRepository,
            DetectionJobRepository jobRepository,
            SettingsService settingsService
    ) {
        this.suggestionRepository = suggestionRepository;
        this.hitRepository = hitRepository;
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

    @Transactional
    public List<ClipSuggestionResponse> createSuggestions(Long jobId, double paddingSeconds) {
        DetectionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "检测任务不存在"));
        double padding = Math.max(0, paddingSeconds);
        suggestionRepository.deleteByJobId(jobId);
        List<TermHit> hits = hitRepository.findByJobIdAndReviewStatusOrderByStartTimeAsc(jobId, ReviewStatus.VIOLATION);
        List<ClipSuggestion> suggestions = hits.stream().map(hit -> {
            ClipSuggestion suggestion = new ClipSuggestion();
            suggestion.setJobId(job.getId());
            suggestion.setHitId(hit.getId());
            suggestion.setPaddingSeconds(padding);
            suggestion.setStartTime(Math.max(0, hit.getStartTime() - padding));
            suggestion.setEndTime(hit.getEndTime() + padding);
            suggestion.setStatus(ClipStatus.PENDING);
            return suggestion;
        }).toList();
        return suggestionRepository.saveAll(suggestions).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ClipSuggestionResponse> listSuggestions(Long jobId) {
        return suggestionRepository.findByJobIdOrderByStartTimeAsc(jobId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ClipSuggestionResponse updateSuggestion(Long id, ClipSuggestionUpdateRequest request) {
        ClipSuggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "剪辑建议不存在"));
        if (request.startTime() != null) {
            suggestion.setStartTime(Math.max(0, request.startTime()));
        }
        if (request.endTime() != null) {
            suggestion.setEndTime(Math.max(suggestion.getStartTime() + 0.1, request.endTime()));
        }
        suggestion.setStatus(request.status());
        suggestion.setUpdatedAt(Instant.now());
        return toResponse(suggestionRepository.save(suggestion));
    }

    private ClipSuggestionResponse toResponse(ClipSuggestion suggestion) {
        TermHit hit = hitRepository.findById(suggestion.getHitId()).orElse(null);
        String matchedText = hit == null ? "-" : hit.getMatchedText();
        Double aiConfidence = hit == null ? null : hit.getAiConfidence();
        TranscriptSource source = hit == null || hit.getSource() == null ? TranscriptSource.AUDIO : hit.getSource();
        return ClipSuggestionResponse.from(suggestion, matchedText, source, aiConfidence);
    }
}
