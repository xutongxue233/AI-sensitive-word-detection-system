package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.ApiType;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import com.ai.moderation.service.support.ExtractedHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AiExtractionServiceTest {
    private SettingsService settingsService;
    private AiModerationClient moderationClient;
    private TransactionTemplate transactionTemplate;
    private AiReviewService aiReviewService;
    private TermHitRepository hitRepository;
    private AiReviewRepository reviewRepository;
    private TranscriptSegmentRepository segmentRepository;
    private TranscriptWordRepository wordRepository;
    private ViolationTermRepository termRepository;
    private AiExtractionService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        moderationClient = mock(AiModerationClient.class);
        transactionTemplate = mock(TransactionTemplate.class);
        aiReviewService = mock(AiReviewService.class);
        hitRepository = mock(TermHitRepository.class);
        reviewRepository = mock(AiReviewRepository.class);
        segmentRepository = mock(TranscriptSegmentRepository.class);
        wordRepository = mock(TranscriptWordRepository.class);
        termRepository = mock(ViolationTermRepository.class);
        TextNormalizer textNormalizer = new TextNormalizer();
        SegmentTimeLocator locator = new SegmentTimeLocator(textNormalizer);
        service = new AiExtractionService(
                settingsService, transactionTemplate, moderationClient, aiReviewService, locator, textNormalizer,
                hitRepository, reviewRepository, segmentRepository, wordRepository, termRepository
        );
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        // 词级时间戳缺省返回空,提取命中退回段级时间(定位逻辑由 SegmentTimeLocatorTest 单独覆盖)
        when(wordRepository.findBySegmentIdOrderBySequenceNoAsc(anyLong())).thenReturn(List.of());
    }

    @Test
    void aiDisabledDelegatesToReviewJob() {
        when(settingsService.currentAi()).thenReturn(ai(false, 0.6));

        service.extractAndReview(1L);

        verify(aiReviewService).reviewJob(1L);
        verify(moderationClient, never()).extract(anyList(), anyList());
        verify(segmentRepository, never()).findByJobIdOrderBySequenceNoAsc(anyLong());
    }

    @Test
    void allBatchesFailFallsBackToReviewJob() {
        when(settingsService.currentAi()).thenReturn(ai(true, 0.6));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(segment(0, "文本", 0, 4, 100L)));
        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term(40L, "价格", "价格", Severity.MEDIUM, MatchType.SEMANTIC)));
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of());
        when(moderationClient.extract(anyList(), anyList())).thenThrow(new RuntimeException("boom"));

        service.extractAndReview(1L);

        verify(moderationClient).extract(anyList(), anyList());
        verify(aiReviewService).reviewJob(1L);
        verify(hitRepository, never()).deleteByJobId(anyLong());
    }

    @Test
    void highConfidenceHitBecomesViolationAndStoresReview() {
        when(settingsService.currentAi()).thenReturn(ai(true, 0.6));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(segment(0, "这款面膜全网最低", 0, 4, 100L)));
        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term(40L, "全网最低", "广告极限词", Severity.CRITICAL, MatchType.EXACT)));
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of());
        when(moderationClient.extract(anyList(), anyList())).thenReturn(List.of(
                extracted(0, "全网最低", "全网最低", "广告极限词", "CRITICAL", 0.9, "绝对化用语")));

        service.extractAndReview(1L);

        ArgumentCaptor<TermHit> hitCaptor = ArgumentCaptor.forClass(TermHit.class);
        verify(hitRepository).save(hitCaptor.capture());
        TermHit saved = hitCaptor.getValue();
        assertThat(saved.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(saved.getAiConfidence()).isEqualTo(0.9);
        assertThat(saved.getCategory()).isEqualTo("广告极限词");
        assertThat(saved.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(saved.getTermId()).isEqualTo(40L);
        assertThat(saved.getMatchedText()).isEqualTo("全网最低");

        ArgumentCaptor<AiReview> reviewCaptor = ArgumentCaptor.forClass(AiReview.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        AiReview review = reviewCaptor.getValue();
        assertThat(review.isViolation()).isTrue();
        assertThat(review.getConfidence()).isEqualTo(0.9);
        assertThat(review.getReason()).isEqualTo("绝对化用语");
    }

    @Test
    void lowConfidenceHitBecomesSafe() {
        when(settingsService.currentAi()).thenReturn(ai(true, 0.6));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(segment(0, "文本价格", 0, 4, 100L)));
        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term(40L, "价格", "价格", Severity.MEDIUM, MatchType.SEMANTIC)));
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of());
        when(moderationClient.extract(anyList(), anyList())).thenReturn(List.of(
                extracted(0, "价格", "价格", "价格", "MEDIUM", 0.4, "可能命中")));

        service.extractAndReview(1L);

        ArgumentCaptor<TermHit> hitCaptor = ArgumentCaptor.forClass(TermHit.class);
        verify(hitRepository).save(hitCaptor.capture());
        assertThat(hitCaptor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.SAFE);
    }

    @Test
    void coveredRuleHitDroppedAndResidualReviewed() {
        when(settingsService.currentAi()).thenReturn(ai(true, 0.6));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(segment(0, "全网最低 九块九", 0, 4, 100L)));
        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(
                term(40L, "全网最低", "广告极限词", Severity.CRITICAL, MatchType.EXACT),
                term(41L, "价格", "价格", Severity.MEDIUM, MatchType.SEMANTIC)));
        // 规则候选:一条与 AI 命中同处(同段+重叠+同分类)将被去重,另一条不同分类保留为残余
        TermHit covered = ruleHit(1000L, 100L, 40L, "广告极限词", 1.0, 2.0);
        TermHit residual = ruleHit(1001L, 100L, 41L, "价格", 1.0, 2.0);
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of(covered, residual));
        when(moderationClient.extract(anyList(), anyList())).thenReturn(List.of(
                extracted(0, "全网最低", "全网最低", "广告极限词", "CRITICAL", 0.9, "绝对化用语")));

        service.extractAndReview(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TermHit>> residualCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiReviewService).reviewHits(residualCaptor.capture(), any(AiProperties.class));
        List<TermHit> reviewed = residualCaptor.getValue();
        assertThat(reviewed).hasSize(1);
        assertThat(reviewed.getFirst().getTermId()).isEqualTo(41L);
    }

    @Test
    void outOfRangeSequenceIsDiscarded() {
        when(settingsService.currentAi()).thenReturn(ai(true, 0.6));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(segment(0, "文本", 0, 4, 100L)));
        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term(40L, "价格", "价格", Severity.MEDIUM, MatchType.SEMANTIC)));
        when(hitRepository.findByJobIdOrderByStartTimeAsc(1L)).thenReturn(List.of());
        when(moderationClient.extract(anyList(), anyList())).thenReturn(List.of(
                extracted(99, "幽灵命中", "价格", "价格", "MEDIUM", 0.9, "段号越界")));

        service.extractAndReview(1L);

        verify(hitRepository, never()).save(any(TermHit.class));
        verify(reviewRepository, never()).save(any(AiReview.class));
    }

    private AiProperties ai(boolean enabled, double threshold) {
        return new AiProperties(enabled, ApiType.CHAT, "http://x", "", "m", 0, threshold, 60);
    }

    private TranscriptSegment segment(int seq, String text, double start, double end, long id) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(id);
        segment.setJobId(1L);
        segment.setSequenceNo(seq);
        segment.setStartTime(start);
        segment.setEndTime(end);
        segment.setText(text);
        return segment;
    }

    private ViolationTerm term(long id, String value, String category, Severity severity, MatchType matchType) {
        ViolationTerm term = new ViolationTerm();
        term.setId(id);
        term.setTerm(value);
        term.setCategory(category);
        term.setSeverity(severity);
        term.setMatchType(matchType);
        return term;
    }

    private ExtractedHit extracted(int seq, String matchedText, String term, String category,
                                   String severity, double confidence, String reason) {
        return new ExtractedHit(seq, matchedText, term, category, severity, confidence, reason);
    }

    private TermHit ruleHit(long id, long segmentId, Long termId, String category, double start, double end) {
        TermHit hit = new TermHit();
        hit.setId(id);
        hit.setJobId(1L);
        hit.setSegmentId(segmentId);
        hit.setTermId(termId);
        hit.setCategory(category);
        hit.setStartTime(start);
        hit.setEndTime(end);
        return hit;
    }
}
