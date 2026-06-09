package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.service.support.AiDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 逐条复核服务(回退策略):对规则召回的候选命中逐条判定违规与否,并按置信阈值把关写
 * {@link ReviewStatus}。是整篇提取(主策略)之外的兜底,用于 AI 未启用、整篇提取整体失败、
 * 或整篇提取未覆盖的规则残余候选。
 *
 * <p>三种来源的置信度策略:
 * <ul>
 *   <li>AI 启用且模型返回 → 用模型给出的置信度;</li>
 *   <li>AI 复核异常(网络/解析失败)→ 保守置 0.60,标记疑似违规但低于默认阈值,交人工确认;</li>
 *   <li>AI 未启用(本地兜底)→ 置 0.70,保留规则命中为疑似违规,提示人工复核。</li>
 * </ul>
 */
@Service
public class AiReviewService {
    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    private final SettingsService settingsService;
    private final TransactionTemplate transactionTemplate;
    private final AiModerationClient moderationClient;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;

    public AiReviewService(
            SettingsService settingsService,
            TransactionTemplate transactionTemplate,
            AiModerationClient moderationClient,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository
    ) {
        this.settingsService = settingsService;
        this.transactionTemplate = transactionTemplate;
        this.moderationClient = moderationClient;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
    }

    /** 复核某任务全部命中,使用当前运行时 AI 设置(启用状态/阈值即时生效)。 */
    public void reviewJob(Long jobId) {
        reviewHits(hitRepository.findByJobIdOrderByStartTimeAsc(jobId), settingsService.currentAi());
    }

    /**
     * 对给定命中逐条复核并按阈值把关。AI 启用时调用模型,未启用时保留规则命中。
     * 传入的命中可为已持久化记录(update)或新建命中(insert),由 save 按 id 决定;
     * 先落库拿到自增 id,再写对应 ai_reviews,保证新建命中的 hitId 不为空。
     */
    public void reviewHits(List<TermHit> hits, AiProperties ai) {
        double threshold = ai.confidenceThreshold();
        for (TermHit hit : hits) {
            AiDecision decision = ai.enabled() ? review(hit) : localFallback(hit);
            transactionTemplate.executeWithoutResult(status -> persistReview(hit, decision, threshold));
        }
    }

    private void persistReview(TermHit hit, AiDecision decision, double threshold) {
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

    /** 调用模型复核单条命中;异常时不抛出,降级为保守判定保留规则命中结果。 */
    private AiDecision review(TermHit hit) {
        try {
            return moderationClient.review(hit);
        } catch (Exception ex) {
            log.warn("AI 复核失败 hitId={} matchedText={} : {}", hit.getId(), hit.getMatchedText(), ex.toString());
            String reason = "AI 复核失败，保留规则命中结果：" + ex.getMessage();
            // 复核失败兜底:0.60 故意低于默认阈值(0.6 边界),保留为疑似违规但不自动判违规,交人工确认。
            return new AiDecision(true, 0.60, hit.getCategory(), reason, reason);
        }
    }

    /** AI 未启用时的本地兜底:不调用模型,直接保留规则命中为疑似违规。 */
    private AiDecision localFallback(TermHit hit) {
        String reason = "AI 未启用，系统保留规则命中结果，建议人工确认。";
        // 本地兜底:0.70 高于默认阈值以便规则命中默认进入时间轴,但仍标注建议人工确认。
        return new AiDecision(true, 0.70, hit.getCategory(), reason, reason);
    }
}
