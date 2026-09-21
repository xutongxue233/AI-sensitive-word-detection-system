package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.config.AiProperties;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import com.ai.moderation.service.support.ExtractedHit;
import com.ai.moderation.service.support.PreparedAiHit;
import com.ai.moderation.service.support.SegmentTimeRange;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * AI 整篇提取召回:把整篇字幕 + 全量词库交给模型,一次性提取所有命中敏感词库的片段,
 * 用词级时间戳定位起止秒,与规则召回候选合并去重,再按置信度阈值把关写入。
 * AI 未启用或整篇提取整体失败时,回退 AiReviewService 的「规则候选逐条复核」(向后兼容)。
 */
@Service
public class AiExtractionService {
    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);
    private static final int BATCH_SIZE = 40;
    private static final int EXTRACT_CONCURRENCY = 4;
    private static final double OVERLAP_TOLERANCE = 0.1;

    // 批次并行的专用线程池:与 buildTranscript 的 transcriptExecutor 同理,故意不注册为 Spring Bean,
    // 与 @Async 框架执行器彻底分离,避免 processAsync 等待批次时同池自饥饿死锁。
    // 并发度收敛在 4,既能摊平批次串行等待,又不至于触发模型网关限流。
    private final ExecutorService extractExecutor = Executors.newFixedThreadPool(EXTRACT_CONCURRENCY, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("ai-extract-" + thread.threadId());
        thread.setDaemon(true);
        return thread;
    });

    private final SettingsService settingsService;
    private final TransactionTemplate transactionTemplate;
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
            TransactionTemplate transactionTemplate,
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
        this.transactionTemplate = transactionTemplate;
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

    /** 容器销毁时优雅关闭提取线程池,避免守护线程残留。 */
    @PreDestroy
    public void shutdownExtractExecutor() {
        extractExecutor.shutdown();
    }

    /** 不关心阶段内进度的入口,等价于传入空回调。 */
    public void extractAndReview(Long jobId) {
        extractAndReview(jobId, progress -> { });
    }

    /**
     * AI 复核阶段入口:决定走「整篇提取」主路径还是「逐条复核」回退路径。
     * AI 未启用、或无字幕/无词库时,直接回退 {@link AiReviewService#reviewJob}(规则候选逐条复核)。
     * 否则把字幕按 {@link #BATCH_SIZE} 分批,在 {@link #extractExecutor} 上以 {@link #EXTRACT_CONCURRENCY}
     * 并发交给模型整篇提取命中(批与批相互独立,并行不影响结果,仅缩短墙钟时间):
     * 只要有任一批成功即采用提取结果(失败批仅告警跳过,不拖垮整体);全部批失败才整体回退逐条复核。
     * 提取命中经去重、与规则候选合并去重后,清掉原始候选并按置信度阈值落库;
     * 未被任一 AI 命中覆盖的规则残余候选仍交逐条复核兜底。
     *
     * @param jobId         当前检测任务 id
     * @param stageProgress 阶段内进度回调(0~100,按已完成批次数推进),由管线映射到全局进度
     */
    public void extractAndReview(Long jobId, IntConsumer stageProgress) {
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
        List<TranscriptSegment> aiSegments = segments.stream()
                .filter(segment -> !isLikelyOcrNoise(segment))
                .toList();
        if (aiSegments.isEmpty()) {
            log.info("AI 整篇提取跳过 jobId={},可用字幕段为空,回退规则候选逐条复核", jobId);
            aiReviewService.reviewJob(jobId);
            return;
        }
        if (aiSegments.size() < segments.size()) {
            log.info("AI 整篇提取已过滤疑似 OCR 噪声段 jobId={} skipped={} total={}",
                    jobId, segments.size() - aiSegments.size(), segments.size());
        }

        int batchCount = (aiSegments.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        log.info("AI 整篇提取开始 jobId={} segments={} terms={} batchSize={} batches={} concurrency={}",
                jobId, aiSegments.size(), terms.size(), BATCH_SIZE, batchCount, EXTRACT_CONCURRENCY);
        List<CompletableFuture<List<ExtractedHit>>> futures = new ArrayList<>(batchCount);
        for (int from = 0; from < aiSegments.size(); from += BATCH_SIZE) {
            List<TranscriptSegment> batch = aiSegments.subList(from, Math.min(aiSegments.size(), from + BATCH_SIZE));
            int batchNo = from / BATCH_SIZE + 1;
            futures.add(CompletableFuture.supplyAsync(() -> {
                log.info("AI 整篇提取批次开始 jobId={} batch={}/{} size={}", jobId, batchNo, batchCount, batch.size());
                List<ExtractedHit> batchHits = moderationClient.extract(batch, terms);
                log.info("AI 整篇提取批次完成 jobId={} batch={}/{} hits={}", jobId, batchNo, batchCount, batchHits.size());
                return batchHits;
            }, extractExecutor));
        }

        // 按提交顺序逐个 join 汇总:失败批仅告警跳过;进度在调用线程上报,避免并发写任务行
        List<ExtractedHit> extracted = new ArrayList<>();
        boolean anyBatchOk = false;
        for (int i = 0; i < futures.size(); i++) {
            try {
                extracted.addAll(futures.get(i).join());
                anyBatchOk = true;
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                log.warn("AI 整篇提取批次失败 jobId={} batch={}/{} : {}", jobId, i + 1, batchCount, cause.toString());
            }
            stageProgress.accept((i + 1) * 100 / batchCount);
        }
        if (!anyBatchOk) {
            log.warn("AI 整篇提取全部失败 jobId={},回退规则候选逐条复核", jobId);
            aiReviewService.reviewJob(jobId);
            return;
        }

        List<PreparedAiHit> aiHits = dedupe(toAiHits(jobId, segments, terms, extracted), ai.confidenceThreshold());
        List<TermHit> residualRuleHits = residualRuleHits(ruleHits, aiHits);

        persistExtractedHits(jobId, aiHits, ai.confidenceThreshold());
        for (TermHit residual : residualRuleHits) {
            residual.setId(null);
        }
        aiReviewService.reviewHits(residualRuleHits, ai);
    }

    private void persistExtractedHits(Long jobId, List<PreparedAiHit> aiHits, double threshold) {
        // 清掉 matchJob 的原始候选,再写入 AI 提取命中;规则残余随后逐条复核并独立落库。
        transactionTemplate.executeWithoutResult(status -> {
            hitRepository.deleteByJobId(jobId);
            for (PreparedAiHit prepared : aiHits) {
                persistAiHit(prepared, threshold);
            }
        });
    }

    /**
     * 把模型返回的提取命中({@link ExtractedHit})转成可落库的 {@link TermHit}(包裹为 {@link PreparedAiHit} 以携带原因)。
     * 用 sequenceNo 回查所属段(段号越界则丢弃,不按下标盲猜定位),再用段内词级时间戳定位 matchedText 的起止秒;
     * 词库词条按归一化文本/分类回查,匹配到则继承其分类/严重级别/匹配类型,否则用模型给出的值兜底。
     * 词列表按段缓存避免重复查询。
     *
     * @param jobId     当前任务 id
     * @param segments  本任务全部转写段(按段号回查与构建上下文)
     * @param terms     全量启用词库(用于回查命中对应的词条)
     * @param extracted 模型返回的提取命中
     * @return 已定位时间轴、可落库的 AI 命中列表
     */
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
            SegmentTimeRange range = segmentTimeLocator.locate(segment, words, eh.matchedText());
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
     * AI 命中内部去重:同段 + 时间区间重叠 + (同 termId/同分类/命中文本互为包含) 视为同一处。
     * 先保留达到置信度阈值的命中；两者都达到或都未达到时，再保留置信度更高者，
     * 最后才用命中文本长度解决仍然相同的情况，避免低置信度长文本覆盖可靠短命中。
     */
    private List<PreparedAiHit> dedupe(List<PreparedAiHit> candidates, double threshold) {
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
            } else if (isMoreSpecific(candidate.hit(), duplicate.hit(), threshold)) {
                kept.remove(duplicate);
                kept.add(candidate);
            }
        }
        return kept;
    }

    /** 去重保留策略:先比较是否达到阈值，再比较置信度，最后比较命中文本长度。 */
    private boolean isMoreSpecific(TermHit candidate, TermHit existing, double threshold) {
        boolean candidatePasses = confidence(candidate) >= threshold;
        boolean existingPasses = confidence(existing) >= threshold;
        if (candidatePasses != existingPasses) {
            return candidatePasses;
        }
        if (Double.compare(confidence(candidate), confidence(existing)) != 0) {
            return confidence(candidate) > confidence(existing);
        }
        int candidateLength = normalizedLength(candidate);
        int existingLength = normalizedLength(existing);
        if (candidateLength != existingLength) {
            return candidateLength > existingLength;
        }
        return false;
    }

    private int normalizedLength(TermHit hit) {
        return textNormalizer.normalizeForMatch(hit.getMatchedText() == null ? "" : hit.getMatchedText()).length();
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
        return sameTerm || sameCategory || isTextContained(a, b);
    }

    /**
     * 命中文本归一化后互为包含(如「几十块」与「几十」)也视为同一处:
     * 同位置文本命中多个词条时跨词条/跨分类去重,只展示更具体的一条。
     */
    private boolean isTextContained(TermHit a, TermHit b) {
        String normalizedA = textNormalizer.normalizeForMatch(a.getMatchedText() == null ? "" : a.getMatchedText());
        String normalizedB = textNormalizer.normalizeForMatch(b.getMatchedText() == null ? "" : b.getMatchedText());
        if (normalizedA.isBlank() || normalizedB.isBlank()) {
            return false;
        }
        return normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA);
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

    private static boolean isLikelyOcrNoise(TranscriptSegment segment) {
        if (segment.getSource() != TranscriptSource.VIDEO_SUBTITLE) {
            return false;
        }
        String compact = compactSignal(segment.getText());
        return compact.length() <= 1 || isAsciiLetters(compact) && compact.length() <= 3;
    }

    private static String compactSignal(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static boolean isAsciiLetters(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))) {
                return false;
            }
        }
        return true;
    }
}
