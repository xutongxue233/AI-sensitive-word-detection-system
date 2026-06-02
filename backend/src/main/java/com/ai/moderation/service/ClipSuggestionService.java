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

    public ClipSuggestionService(
            ClipSuggestionRepository suggestionRepository,
            TermHitRepository hitRepository,
            DetectionJobRepository jobRepository
    ) {
        this.suggestionRepository = suggestionRepository;
        this.hitRepository = hitRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public List<ClipSuggestionResponse> createSuggestions(Long jobId, double paddingSeconds) {
        DetectionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "检测任务不存在"));
        suggestionRepository.deleteByJobId(jobId);
        List<TermHit> hits = hitRepository.findByJobIdAndReviewStatusOrderByStartTimeAsc(jobId, ReviewStatus.VIOLATION);
        List<ClipSuggestion> suggestions = hits.stream().map(hit -> {
            ClipSuggestion suggestion = new ClipSuggestion();
            suggestion.setJobId(job.getId());
            suggestion.setHitId(hit.getId());
            suggestion.setPaddingSeconds(paddingSeconds);
            suggestion.setStartTime(Math.max(0, hit.getStartTime() - paddingSeconds));
            suggestion.setEndTime(hit.getEndTime() + paddingSeconds);
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
        String matchedText = hitRepository.findById(suggestion.getHitId())
                .map(TermHit::getMatchedText)
                .orElse("-");
        return ClipSuggestionResponse.from(suggestion, matchedText);
    }
}
