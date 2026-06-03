package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.domain.*;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import com.ai.moderation.service.SegmentTimeLocator.SegmentIndex;
import com.ai.moderation.service.SegmentTimeLocator.TimeRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class RuleMatchingService {
    private static final Pattern ARABIC_PRICE_WITH_UNIT = Pattern.compile(
            "(?:￥|¥|RMB\\s*)?\\d+(?:\\.\\d{1,2})?(?:多|几)?\\s*(?:块钱|块|元|毛|分|人民币|rmb)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern CHINESE_PRICE_WITH_UNIT = Pattern.compile(
            "[零一二三四五六七八九十百千万两几]+(?:多)?\\s*(?:块钱|块|元|毛|分|人民币)"
    );
    private static final Pattern PRICE_SYMBOL_OR_DECIMAL = Pattern.compile(
            "(?:￥|¥)\\s*\\d+(?:\\.\\d{1,2})?|\\d+\\.\\d{1,2}"
    );
    // 口语/约数价格(无单位):60几、60多、几十、十几、几百、两三百等;仅在价格上下文中召回,交 AI 判定真伪
    private static final Pattern APPROX_PRICE = Pattern.compile(
            "\\d+\\s*[多几]|[一二两三四五六七八九十百千万]+\\s*[多几]|几\\s*[十百千万]|[十百千万]+\\s*几"
    );
    private static final Pattern PRICE_CONTEXT = Pattern.compile(
            "价格|售价|报价|多少钱|多少米|到手|优惠|折扣|下单|付款|支付|返现|立减|包邮|买|卖|才|要|花|值|元|块|rmb|人民币",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final ViolationTermRepository termRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptWordRepository wordRepository;
    private final TermHitRepository hitRepository;
    private final TextNormalizer textNormalizer;
    private final SegmentTimeLocator segmentTimeLocator;

    public RuleMatchingService(
            ViolationTermRepository termRepository,
            TranscriptSegmentRepository segmentRepository,
            TranscriptWordRepository wordRepository,
            TermHitRepository hitRepository,
            TextNormalizer textNormalizer,
            SegmentTimeLocator segmentTimeLocator
    ) {
        this.termRepository = termRepository;
        this.segmentRepository = segmentRepository;
        this.wordRepository = wordRepository;
        this.hitRepository = hitRepository;
        this.textNormalizer = textNormalizer;
        this.segmentTimeLocator = segmentTimeLocator;
    }

    @Transactional
    public List<TermHit> matchJob(DetectionJob job) {
        hitRepository.deleteByJobId(job.getId());
        List<ViolationTerm> terms = termRepository.findByEnabledTrueOrderByUpdatedAtDesc();
        List<TranscriptSegment> segments = segmentRepository.findByJobIdOrderBySequenceNoAsc(job.getId());
        List<TermHit> hits = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment segment = segments.get(i);
            String context = segmentTimeLocator.buildContext(segments, i);
            List<TranscriptWord> words = wordRepository.findBySegmentIdOrderBySequenceNoAsc(segment.getId());
            SegmentIndex index = segmentTimeLocator.index(segment, words);
            for (ViolationTerm term : terms) {
                hits.addAll(matchTerm(job, segment, context, index, term));
            }
        }
        return hitRepository.saveAll(hits);
    }

    private List<TermHit> matchTerm(DetectionJob job, TranscriptSegment segment, String context, SegmentIndex index, ViolationTerm term) {
        if (term.getMatchType() == MatchType.REGEX) {
            return matchRegex(job, segment, context, term);
        }
        if (term.getMatchType() == MatchType.SEMANTIC) {
            return matchSemantic(job, segment, context, term);
        }
        List<TermHit> hits = new ArrayList<>();
        for (String candidate : candidates(term)) {
            String normalizedCandidate = textNormalizer.normalizeForMatch(candidate);
            if (normalizedCandidate.isBlank()) {
                continue;
            }
            int from = 0;
            while (from < index.normalizedText().length()) {
                int found = index.normalizedText().indexOf(normalizedCandidate, from);
                if (found < 0) {
                    break;
                }
                TimeRange timeRange = index.locate(found, found + normalizedCandidate.length());
                hits.add(createHit(job, segment, term, candidate, context, timeRange.start(), timeRange.end()));
                from = found + Math.max(1, normalizedCandidate.length());
            }
        }
        return hits;
    }

    private List<TermHit> matchSemantic(DetectionJob job, TranscriptSegment segment, String context, ViolationTerm term) {
        if (!isPriceSemanticTerm(term)) {
            return List.of();
        }
        List<TermHit> hits = new ArrayList<>();
        String text = segment.getText() == null ? "" : segment.getText();
        boolean priceContext = PRICE_CONTEXT.matcher(text).find();
        collectPriceMatches(job, segment, context, term, ARABIC_PRICE_WITH_UNIT, hits, true);
        collectPriceMatches(job, segment, context, term, CHINESE_PRICE_WITH_UNIT, hits, true);
        collectPriceMatches(job, segment, context, term, PRICE_SYMBOL_OR_DECIMAL, hits, priceContext);
        collectPriceMatches(job, segment, context, term, APPROX_PRICE, hits, priceContext);
        return hits;
    }

    private void collectPriceMatches(
            DetectionJob job,
            TranscriptSegment segment,
            String context,
            ViolationTerm term,
            Pattern pattern,
            List<TermHit> hits,
            boolean enabled
    ) {
        if (!enabled || segment.getText() == null) {
            return;
        }
        Matcher matcher = pattern.matcher(segment.getText());
        while (matcher.find()) {
            double start = mapSourceOffset(segment, matcher.start());
            double end = mapSourceOffset(segment, matcher.end());
            hits.add(createHit(job, segment, term, matcher.group().trim(), context, start, Math.max(end, start + 0.2)));
        }
    }

    private boolean isPriceSemanticTerm(ViolationTerm term) {
        String normalized = textNormalizer.normalizeForMatch((term.getTerm() == null ? "" : term.getTerm())
                + " " + (term.getCategory() == null ? "" : term.getCategory()));
        return normalized.contains("价格")
                || normalized.contains("金额")
                || normalized.contains("报价")
                || normalized.contains("售价")
                || normalized.contains("price");
    }

    private List<TermHit> matchRegex(DetectionJob job, TranscriptSegment segment, String context, ViolationTerm term) {
        List<TermHit> hits = new ArrayList<>();
        try {
            Pattern pattern = Pattern.compile(term.getTerm(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher matcher = pattern.matcher(segment.getText());
            while (matcher.find()) {
                double start = mapSourceOffset(segment, matcher.start());
                double end = mapSourceOffset(segment, matcher.end());
                hits.add(createHit(job, segment, term, matcher.group(), context, start, Math.max(end, start + 0.2)));
            }
        } catch (PatternSyntaxException ignored) {
            // Invalid regex terms are skipped so one bad rule does not block the whole detection job.
        }
        return hits;
    }

    private TermHit createHit(
            DetectionJob job,
            TranscriptSegment segment,
            ViolationTerm term,
            String matchedText,
            String context,
            double startTime,
            double endTime
    ) {
        TermHit hit = new TermHit();
        hit.setJobId(job.getId());
        hit.setSegmentId(segment.getId());
        hit.setTermId(term.getId());
        hit.setMatchedText(matchedText);
        hit.setCategory(term.getCategory());
        hit.setSeverity(term.getSeverity());
        hit.setRuleSource(term.getMatchType());
        hit.setSource(segment.getSource() == null ? TranscriptSource.AUDIO : segment.getSource());
        hit.setStartTime(Math.max(segment.getStartTime(), startTime));
        hit.setEndTime(Math.min(segment.getEndTime(), Math.max(endTime, startTime + 0.2)));
        hit.setContextText(context);
        hit.setReviewStatus(ReviewStatus.PENDING);
        return hit;
    }

    private List<String> candidates(ViolationTerm term) {
        List<String> values = new ArrayList<>();
        values.add(term.getTerm());
        if (term.getMatchType() == MatchType.VARIANT && term.getVariants() != null) {
            values.addAll(Arrays.stream(term.getVariants().split("[,，\\n\\r]+"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList());
        }
        return values;
    }

    private double mapSourceOffset(TranscriptSegment segment, int sourceOffset) {
        if (segment.getText() == null || segment.getText().isBlank()) {
            return segment.getStartTime();
        }
        double ratio = Math.min(1.0, Math.max(0, sourceOffset / (double) segment.getText().length()));
        return segment.getStartTime() + (segment.getEndTime() - segment.getStartTime()) * ratio;
    }
}
