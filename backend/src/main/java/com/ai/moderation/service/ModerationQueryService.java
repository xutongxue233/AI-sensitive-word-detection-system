package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.*;
import com.ai.moderation.dto.*;
import com.ai.moderation.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ModerationQueryService {
    private final DetectionJobRepository jobRepository;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;

    public ModerationQueryService(
            DetectionJobRepository jobRepository,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository
    ) {
        this.jobRepository = jobRepository;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(Long jobId) {
        return JobResponse.from(jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "检测任务不存在")));
    }

    @Transactional(readOnly = true)
    public List<TermHitResponse> listHits(Long jobId) {
        return hitRepository.findByJobIdOrderByStartTimeAsc(jobId)
                .stream()
                .map(hit -> TermHitResponse.from(hit, reviewRepository.findByHitId(hit.getId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimelineItemResponse> timeline(Long jobId) {
        return hitRepository.findByJobIdOrderByStartTimeAsc(jobId)
                .stream()
                .filter(hit -> hit.getReviewStatus() == ReviewStatus.VIOLATION || hit.getReviewStatus() == ReviewStatus.CONFIRMED)
                .map(hit -> new TimelineItemResponse(
                        hit.getId(),
                        hit.getMatchedText(),
                        hit.getCategory(),
                        hit.getSeverity(),
                        hit.getSource(),
                        hit.getReviewStatus(),
                        hit.getStartTime(),
                        hit.getEndTime(),
                        hit.getContextText(),
                        hit.getAiConfidence(),
                        reviewRepository.findByHitId(hit.getId()).map(AiReview::getReason).orElse(null)
                ))
                .toList();
    }

    @Transactional
    public TermHitResponse updateHitStatus(Long hitId, ReviewStatus status) {
        TermHit hit = hitRepository.findById(hitId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "命中记录不存在"));
        hit.setReviewStatus(status);
        TermHit saved = hitRepository.save(hit);
        return TermHitResponse.from(saved, reviewRepository.findByHitId(hitId).orElse(null));
    }

    public record HitStatusRequest(ReviewStatus status) {
    }
}
