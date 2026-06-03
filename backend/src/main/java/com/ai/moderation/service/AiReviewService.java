package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.service.AiModerationClient.AiDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AiReviewService {
    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    private final SettingsService settingsService;
    private final AiModerationClient moderationClient;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;

    public AiReviewService(
            SettingsService settingsService,
            AiModerationClient moderationClient,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository
    ) {
        this.settingsService = settingsService;
        this.moderationClient = moderationClient;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional
    public void reviewJob(Long jobId) {
        reviewHits(hitRepository.findByJobIdOrderByStartTimeAsc(jobId), settingsService.currentAi());
    }

    /**
     * 对给定命中逐条复核并按阈值把关。AI 启用时调用模型,未启用时保留规则命中。
     * 传入的命中可为已持久化记录(update)或新建命中(insert),由 save 按 id 决定;
     * 先落库拿到自增 id,再写对应 ai_reviews,保证新建命中的 hitId 不为空。
     */
    @Transactional
    public void reviewHits(List<TermHit> hits, AiProperties ai) {
        double threshold = ai.confidenceThreshold();
        for (TermHit hit : hits) {
            AiDecision decision = ai.enabled() ? review(hit) : localFallback(hit);

            // 置信度把关:只有 AI 确认命中且置信度达到阈值,才标记为违规进入时间轴/剪辑。
            // 低置信命中保留记录与原因,但置为 SAFE,不会自动生成剪辑。
            boolean pass = decision.violation() && decision.confidence() >= threshold;
            hit.setReviewStatus(pass ? ReviewStatus.VIOLATION : ReviewStatus.SAFE);
            hit.setAiConfidence(decision.confidence());
            hitRepository.save(hit);

            AiReview review = new AiReview();
            review.setHitId(hit.getId());
            review.setViolation(decision.violation());
            review.setConfidence(decision.confidence());
            review.setCategory(decision.category());
            review.setReason(decision.reason());
            review.setRawResponse(decision.rawResponse());
            reviewRepository.save(review);
        }
    }

    private AiDecision review(TermHit hit) {
        try {
            return moderationClient.review(hit);
        } catch (Exception ex) {
            log.warn("AI 复核失败 hitId={} matchedText={} : {}", hit.getId(), hit.getMatchedText(), ex.toString());
            String reason = "AI 复核失败，保留规则命中结果：" + ex.getMessage();
            return new AiDecision(true, 0.60, hit.getCategory(), reason, reason);
        }
    }

    private AiDecision localFallback(TermHit hit) {
        String reason = "AI 未启用，系统保留规则命中结果，建议人工确认。";
        return new AiDecision(true, 0.70, hit.getCategory(), reason, reason);
    }
}
