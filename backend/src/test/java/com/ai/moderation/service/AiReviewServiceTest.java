package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.ApiType;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.service.support.AiDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AiReviewServiceTest {
    private SettingsService settingsService;
    private AiModerationClient moderationClient;
    private TransactionTemplate transactionTemplate;
    private TermHitRepository hitRepository;
    private AiReviewRepository reviewRepository;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        moderationClient = mock(AiModerationClient.class);
        transactionTemplate = mock(TransactionTemplate.class);
        hitRepository = mock(TermHitRepository.class);
        reviewRepository = mock(AiReviewRepository.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private AiReviewService service(double threshold) {
        return service(true, threshold);
    }

    private AiReviewService service(boolean enabled, double threshold) {
        when(settingsService.currentAi())
                .thenReturn(new AiProperties(enabled, ApiType.CHAT, "http://x", "", "m", 0, threshold, 60));
        return new AiReviewService(settingsService, transactionTemplate, moderationClient, hitRepository, reviewRepository);
    }

    private TermHit hit() {
        TermHit hit = new TermHit();
        hit.setId(100L);
        hit.setJobId(1L);
        hit.setMatchedText("全网最低");
        hit.setCategory("广告极限词");
        hit.setSeverity(Severity.CRITICAL);
        hit.setReviewStatus(ReviewStatus.PENDING);
        return hit;
    }

    @Test
    void highConfidenceViolationPassesGate() {
        TermHit hit = hit();
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(hit));
        when(moderationClient.review(hit)).thenReturn(new AiDecision(true, 0.9, "广告极限词", "绝对化用语", "raw"));

        service(0.6).reviewJob(1L);

        assertThat(hit.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(hit.getAiConfidence()).isEqualTo(0.9);
    }

    @Test
    void lowConfidenceViolationIsGatedToSafe() {
        TermHit hit = hit();
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(hit));
        when(moderationClient.review(hit)).thenReturn(new AiDecision(true, 0.4, "广告极限词", "可能违规但不确定", "raw"));

        service(0.6).reviewJob(1L);

        assertThat(hit.getReviewStatus()).isEqualTo(ReviewStatus.SAFE);
        assertThat(hit.getAiConfidence()).isEqualTo(0.4);
    }

    @Test
    void nonViolationIsSafeEvenWithHighConfidence() {
        TermHit hit = hit();
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(hit));
        when(moderationClient.review(hit)).thenReturn(new AiDecision(false, 0.95, "广告极限词", "上下文不构成违规", "raw"));

        service(0.6).reviewJob(1L);

        assertThat(hit.getReviewStatus()).isEqualTo(ReviewStatus.SAFE);
    }

    @Test
    void storesRawAiDecisionRegardlessOfGate() {
        TermHit hit = hit();
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(hit));
        when(moderationClient.review(hit)).thenReturn(new AiDecision(true, 0.4, "广告极限词", "不确定", "raw"));

        service(0.6).reviewJob(1L);

        ArgumentCaptor<AiReview> captor = ArgumentCaptor.forClass(AiReview.class);
        verify(reviewRepository).save(captor.capture());
        AiReview saved = captor.getValue();
        assertThat(saved.getHitId()).isEqualTo(100L);
        assertThat(saved.isViolation()).isTrue();
        assertThat(saved.getConfidence()).isEqualTo(0.4);
        assertThat(saved.getReason()).isEqualTo("不确定");
    }

    @Test
    void keepsRuleHitWithoutCallingAiWhenDisabled() {
        TermHit hit = hit();
        AiReviewService service = service(false, 0.6);
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(hit));

        service.reviewJob(1L);

        verify(moderationClient, never()).review(any());
        assertThat(hit.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(hit.getAiConfidence()).isEqualTo(0.70);
    }

    @Test
    void multipleHitsGoThroughBatchReviewWithoutSingleCalls() {
        TermHit first = hit();
        TermHit second = hit();
        second.setId(101L);
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(first, second));
        when(moderationClient.reviewBatch(anyList())).thenReturn(Arrays.asList(
                new AiDecision(true, 0.9, "广告极限词", "命中", "raw"),
                new AiDecision(false, 0.8, "广告极限词", "同形异义", "raw")));

        service(0.6).reviewJob(1L);

        verify(moderationClient, never()).review(any());
        assertThat(first.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(second.getReviewStatus()).isEqualTo(ReviewStatus.SAFE);
    }

    @Test
    void missingBatchDecisionFallsBackToSingleReview() {
        TermHit first = hit();
        TermHit second = hit();
        second.setId(101L);
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(first, second));
        when(moderationClient.reviewBatch(anyList())).thenReturn(Arrays.asList(
                new AiDecision(true, 0.9, "广告极限词", "命中", "raw"),
                null));
        when(moderationClient.review(second)).thenReturn(new AiDecision(false, 0.7, "广告极限词", "未命中", "raw"));

        service(0.6).reviewJob(1L);

        verify(moderationClient).review(second);
        verify(moderationClient, never()).review(first);
        assertThat(first.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(second.getReviewStatus()).isEqualTo(ReviewStatus.SAFE);
    }

    @Test
    void batchFailureFallsBackToSingleReviewForWholeBatch() {
        TermHit first = hit();
        TermHit second = hit();
        second.setId(101L);
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(first, second));
        when(moderationClient.reviewBatch(anyList())).thenThrow(new RuntimeException("boom"));
        when(moderationClient.review(first)).thenReturn(new AiDecision(true, 0.9, "广告极限词", "命中", "raw"));
        when(moderationClient.review(second)).thenReturn(new AiDecision(false, 0.7, "广告极限词", "未命中", "raw"));

        service(0.6).reviewJob(1L);

        assertThat(first.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(second.getReviewStatus()).isEqualTo(ReviewStatus.SAFE);
    }
}
