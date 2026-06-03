package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.config.AiProperties;
import com.ai.moderation.domain.*;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import com.ai.moderation.service.AiModerationClient.ExtractedHit;
import com.ai.moderation.service.SegmentTimeLocator.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * AI 整篇提取召回:把整篇字幕 + 全量词库交给模型,一次性提取所有命中敏感词库的片段,
 * 用词级时间戳定位起止秒,与规则召回候选合并去重,再按置信度阈值把关写入。
 * AI 未启用或整篇提取整体失败时,回退 AiReviewService 的「规则候选逐条复核」(向后兼容)。
 */
@Service
public class AiExtractionService {
    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);
    private static final int BATCH_SIZE = 40;
    private static final double OVERLAP_TOLERANCE = 0.1;

    private final SettingsService settingsService;
    private final AiModerationClient moderationClient;
    private final AiReviewService aiReviewService;
    private final SegmentTimeLocator segmentTimeLocator;
    private final TextNormalizer textNormalizer;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptWordRepository wordRepository;
    private final ViolationTermRepository termRepository;

    public AiExtractionService(
            SettingsService settingsService,
            AiModerationClient moderationClient,
            AiReviewService aiReviewService,
            SegmentTimeLocator segmentTimeLocator,
            TextNormalizer textNormalizer,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository,
            TranscriptSegmentRepository segmentRepository,
            TranscriptWordRepository wordRepository,
            ViolationTermRepository termRepository
    ) {
        this.settingsService = settingsService;
        this.moderationClient = moderationClient;
        this.aiReviewService = aiReviewService;
        this.segmentTimeLocator = segmentTimeLocator;
        this.textNormalizer = textNormalizer;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
        this.segmentRepository = segmentRepository;
        this.wordRepository = wordRepository;
        this.termRepository = termRepository;
    }

    @Transactional
    public void extractAndReview(Long jobId) {
        AiProperties ai = settingsService.currentAi();
        if (!ai.enabled()) {
            aiReviewService.reviewJob(jobId);
            return;
        }

        List<TranscriptSegment> segments = segmentRepository.findByJobIdOrderBySequenceNoAsc(jobId);
        List<ViolationTerm> terms = termRepository.findByEnabledTrueOrderByUpdatedAtDesc();
        if (segments.isEmpty() || terms.isEmpty()) {
            aiReviewService.reviewJob(jobId);
            return;
        }

        List<TermHit> ruleHits = hitRepository.findByJobIdOrderByStartTimeAsc(jobId);

        List<ExtractedHit> extracted = new ArrayList<>();
        boolean anyBatchOk = false;
        for (int from = 0; from < segments.size(); from += BATCH_SIZE) {
            List<TranscriptSegment> batch = segments.subList(from, Math.min(segments.size(), from + BATCH_SIZE));
            try {
                extracted.addAll(moderationClient.extract(batch, terms));
                anyBatchOk = true;
            } catch (Exception ex) {
                log.warn("AI 整篇提取批次失败 jobId={} batchStart={} : {}", jobId, from, ex.toString());
            }
        }
        if (!anyBatchOk) {
            log.warn("AI 整篇提取全部失败 jobId={},回退规则候选逐条复核", jobId);
            aiReviewService.reviewJob(jobId);
            return;
        }

        List<PreparedAiHit> aiHits = dedupe(toAiHits(jobId, segments, terms, extracted));
        List<TermHit> residualRuleHits = residualRuleHits(ruleHits, aiHits);

        // 清掉 matchJob 的原始候选,再写入「AI 提取命中 + 未被覆盖的规则残余」合并集。
        hitRepository.deleteByJobId(jobId);
        double threshold = ai.confidenceThreshold();
        for (PreparedAiHit prepared : aiHits) {
            persistAiHit(prepared, threshold);
        }
        for (TermHit residual : residualRuleHits) {
            residual.setId(null);
        }
        aiReviewService.reviewHits(residualRuleHits, ai);
    }

    private List<PreparedAiHit> toAiHits(Long jobId, List<TranscriptSegment> segments,
                                         List<ViolationTerm> terms, List<ExtractedHit> extracted) {
        Map<Integer, TranscriptSegment> segmentBySeq = new HashMap<>();
        Map<Integer, Integer> indexBySeq = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            segmentBySeq.put(segments.get(i).getSequenceNo(), segments.get(i));
            indexBySeq.put(segments.get(i).getSequenceNo(), i);
        }

        Map<Long, List<TranscriptWord>> wordsCache = new HashMap<>();
        List<PreparedAiHit> hits = new ArrayList<>();
        for (ExtractedHit eh : extracted) {
            TranscriptSegment segment = segmentBySeq.get(eh.segmentSeq());
            if (segment == null) {
                continue; // 段号越界:丢弃,不按下标盲猜定位
            }
            List<TranscriptWord> words = wordsCache.computeIfAbsent(
                    segment.getId(), wordRepository::findBySegmentIdOrderBySequenceNoAsc);
            TimeRange range = segmentTimeLocator.locate(segment, words, eh.matchedText());
            ViolationTerm term = findTerm(terms, eh);

            TermHit hit = new TermHit();
            hit.setJobId(jobId);
            hit.setSegmentId(segment.getId());
            hit.setTermId(term == null ? null : term.getId());
            hit.setMatchedText(eh.matchedText());
            hit.setCategory(term != null ? term.getCategory() : emptyToNull(eh.category()));
            hit.setSeverity(term != null ? term.getSeverity() : parseSeverity(eh.severity()));
            hit.setRuleSource(term != null ? term.getMatchType() : MatchType.SEMANTIC);
            hit.setSource(segment.getSource() == null ? TranscriptSource.AUDIO : segment.getSource());
            double start = Math.max(segment.getStartTime(), range.start());
            double end = Math.min(segment.getEndTime(), Math.max(range.end(), start + 0.2));
            hit.setStartTime(start);
            hit.setEndTime(end);
            Integer index = indexBySeq.get(eh.segmentSeq());
            hit.setContextText(index == null
                    ? segment.getText()
                    : segmentTimeLocator.buildContext(segments, index));
            hit.setReviewStatus(ReviewStatus.PENDING);
            hit.setAiConfidence(eh.confidence());
            hits.add(new PreparedAiHit(hit, eh.reason()));
        }
        return hits;
    }

    /**
     * AI 命中内部去重:同段 + 时间区间重叠 + 同 termId/同分类 视为同一处,保留置信度最高者。
     */
    private List<PreparedAiHit> dedupe(List<PreparedAiHit> candidates) {
        List<PreparedAiHit> kept = new ArrayList<>();
        for (PreparedAiHit candidate : candidates) {
            PreparedAiHit duplicate = null;
            for (PreparedAiHit existing : kept) {
                if (isSamePlace(candidate.hit(), existing.hit())) {
                    duplicate = existing;
                    break;
                }
            }
            if (duplicate == null) {
                kept.add(candidate);
            } else if (confidence(candidate.hit()) > confidence(duplicate.hit())) {
                kept.remove(duplicate);
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * 规则候选中未被任一 AI 命中覆盖的残余,保留下来仍走逐条复核(规则兜底)。
     */
    private List<TermHit> residualRuleHits(List<TermHit> ruleHits, List<PreparedAiHit> aiHits) {
        List<TermHit> residual = new ArrayList<>();
        for (TermHit rule : ruleHits) {
            boolean covered = false;
            for (PreparedAiHit ai : aiHits) {
                if (isSamePlace(rule, ai.hit())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                residual.add(rule);
            }
        }
        return residual;
    }

    private void persistAiHit(PreparedAiHit prepared, double threshold) {
        TermHit hit = prepared.hit();
        // AI 提取出的片段即视为命中词库(violation=true),仅按置信度决定是否进时间轴/剪辑。
        boolean pass = confidence(hit) >= threshold;
        hit.setReviewStatus(pass ? ReviewStatus.VIOLATION : ReviewStatus.SAFE);
        hit.setId(null);
        hitRepository.save(hit);

        AiReview review = new AiReview();
        review.setHitId(hit.getId());
        review.setViolation(true);
        review.setConfidence(hit.getAiConfidence());
        review.setCategory(hit.getCategory());
        review.setReason(prepared.reason());
        review.setRawResponse(prepared.reason());
        reviewRepository.save(review);
    }

    private boolean isSamePlace(TermHit a, TermHit b) {
        if (!Objects.equals(a.getSegmentId(), b.getSegmentId())) {
            return false;
        }
        double overlapStart = Math.max(a.getStartTime(), b.getStartTime());
        double overlapEnd = Math.min(a.getEndTime(), b.getEndTime());
        if (overlapStart >= overlapEnd + OVERLAP_TOLERANCE) {
            return false;
        }
        boolean sameTerm = a.getTermId() != null && a.getTermId().equals(b.getTermId());
        boolean sameCategory = a.getCategory() != null && a.getCategory().equalsIgnoreCase(b.getCategory());
        return sameTerm || sameCategory;
    }

    private ViolationTerm findTerm(List<ViolationTerm> terms, ExtractedHit eh) {
        String normalizedTerm = textNormalizer.normalizeForMatch(eh.term());
        if (!normalizedTerm.isBlank()) {
            for (ViolationTerm term : terms) {
                if (textNormalizer.normalizeForMatch(term.getTerm()).equals(normalizedTerm)) {
                    return term;
                }
            }
        }
        String category = eh.category();
        if (category != null && !category.isBlank()) {
            for (ViolationTerm term : terms) {
                if (category.equalsIgnoreCase(term.getCategory())) {
                    return term;
                }
            }
        }
        return null;
    }

    private Severity parseSeverity(String value) {
        if (value == null || value.isBlank()) {
            return Severity.MEDIUM;
        }
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Severity.MEDIUM;
        }
    }

    private static double confidence(TermHit hit) {
        return hit.getAiConfidence() == null ? 0 : hit.getAiConfidence();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record PreparedAiHit(TermHit hit, String reason) {
    }
}
