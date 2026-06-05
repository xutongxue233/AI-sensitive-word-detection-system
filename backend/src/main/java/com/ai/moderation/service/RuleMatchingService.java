package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import com.ai.moderation.service.support.SegmentIndex;
import com.ai.moderation.service.support.SegmentTimeRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则召回服务:检测管线 MATCHING_TERMS 阶段的入口。
 * 对每段转写文本逐条比对启用中的敏感词库,按 {@link MatchType} 的四类召回候选命中,
 * 并用段内时间映射把命中定位到时间轴,产出待 AI 复核的候选(置 {@link ReviewStatus#PENDING})。
 * 四类匹配:EXACT 精确、VARIANT 含变体别名、REGEX 正则、SEMANTIC 语义。
 * SEMANTIC 当前仅实现价格语义——靠下方价格正则在价格上下文中召回候选,真伪交后续 AI 判定。
 * 召回只放宽不收紧:宁可多召回交 AI 把关,也不在规则层提前漏掉。
 */
@Service
public class RuleMatchingService {
    /** 阿拉伯数字带单位价格:如「9.9元」「￥30」「100块钱」「20多元」,可选币种前缀与「多/几」约数后缀。 */
    private static final Pattern ARABIC_PRICE_WITH_UNIT = Pattern.compile(
            "(?:￥|¥|RMB\\s*)?\\d+(?:\\.\\d{1,2})?(?:多|几)?\\s*(?:块钱|块|元|毛|分|人民币|rmb)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    /** 中文数字带单位价格:如「九块九」「一百块」「两千元」「三十多块」。 */
    private static final Pattern CHINESE_PRICE_WITH_UNIT = Pattern.compile(
            "[零一二三四五六七八九十百千万两几]+(?:多)?\\s*(?:块钱|块|元|毛|分|人民币)"
    );
    /** 币种符号或小数形态价格(无文字单位):如「￥30」「¥9.9」「19.99」。 */
    private static final Pattern PRICE_SYMBOL_OR_DECIMAL = Pattern.compile(
            "(?:￥|¥)\\s*\\d+(?:\\.\\d{1,2})?|\\d+\\.\\d{1,2}"
    );
    // 口语/约数价格(无单位):60几、60多、几十、十几、几百、两三百等;仅在价格上下文中召回,交 AI 判定真伪
    private static final Pattern APPROX_PRICE = Pattern.compile(
            "\\d+\\s*[多几]|[一二两三四五六七八九十百千万]+\\s*[多几]|几\\s*[十百千万]|[十百千万]+\\s*几"
    );
    /** 价格上下文判别词:出现这些词时,才把无单位的符号/小数/约数价格视为价格表达,降低误召回。 */
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

    /**
     * 对整个检测任务做规则召回:先清除该任务旧候选,逐段比对全部启用词库,落库候选命中。
     * 每段都预构建上下文(前后相邻段)与段内时间索引,供命中定位时间轴与 AI 复核取上下文。
     *
     * @param job 当前检测任务,提供 jobId 关联候选并界定处理范围
     * @return 已落库的候选命中列表(状态均为 PENDING,待 AI 复核)
     */
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

    /**
     * 单段 × 单词条的召回分派:按 {@link MatchType} 分流到不同策略。
     * REGEX/SEMANTIC 走各自专用方法;EXACT/VARIANT 走归一化后的子串扫描——
     * 在归一化文本里反复定位候选,每次命中用 {@link SegmentIndex#locate} 反算回时间轴。
     *
     * @param segment 当前转写段
     * @param context 该段的上下文文本(含相邻段),供命中携带与 AI 复核
     * @param index   该段的归一化文本与字符→时间索引
     * @param term    待比对的词库词条
     * @return 该词条在该段命中的候选列表(可能为空)
     */
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
                SegmentTimeRange timeRange = index.locate(found, found + normalizedCandidate.length());
                hits.add(createHit(job, segment, term, candidate, context, timeRange.start(), timeRange.end()));
                from = found + Math.max(1, normalizedCandidate.length());
            }
        }
        return hits;
    }

    /**
     * 语义召回:当前仅支持价格语义。非价格语义词条直接返回空(尚未实现其他语义类别)。
     * 带文字单位的价格(阿拉伯/中文)无条件召回;符号/小数、约数等弱信号价格仅在价格上下文中召回,
     * 以压低误召回——真伪最终交 AI 判定。
     *
     * @param segment 当前转写段
     * @param context 该段上下文文本
     * @param term    语义类词条
     * @return 价格命中候选列表(非价格语义词条返回空)
     */
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

    /**
     * 用指定价格正则扫描原始段文本(非归一化文本,以保留符号/小数原貌),把每个匹配换算到时间轴后收集为候选。
     * 匹配位置经字符占比线性映射到段内时间,并保证最小 0.2 秒时长避免零时长片段。
     *
     * @param pattern 本轮使用的价格正则
     * @param hits    收集命中的输出列表(原地追加)
     * @param enabled 是否启用本轮匹配(弱信号正则仅在价格上下文成立时为 true)
     */
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

    /**
     * 判断语义词条是否属于价格语义:把词条本身与分类归一化后,命中「价格/金额/报价/售价/price」即视为价格语义。
     * 用于 {@link #matchSemantic} 过滤掉暂不支持的其他语义类别。
     */
    private boolean isPriceSemanticTerm(ViolationTerm term) {
        String normalized = textNormalizer.normalizeForMatch((term.getTerm() == null ? "" : term.getTerm())
                + " " + (term.getCategory() == null ? "" : term.getCategory()));
        return normalized.contains("价格")
                || normalized.contains("金额")
                || normalized.contains("报价")
                || normalized.contains("售价")
                || normalized.contains("price");
    }

    /**
     * 正则召回:用词条自身作为正则在段文本上查找,每个匹配换算到时间轴后收集为候选。
     * 词条正则非法时跳过该词条而非中断整个任务(见 catch 注释)。
     */
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
            // 跳过非法正则词条,避免一条坏规则拖垮整个检测任务。
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

    /**
     * 收集词条用于子串扫描的所有候选写法:始终含词条本身;
     * VARIANT 类型再补上 variants 字段里以逗号/中英文逗号/换行分隔的别名,空白项剔除。
     */
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

    /**
     * 把段文本中的字符偏移近似映射回时间轴:按偏移占文本长度的比例,在该段起止时间内线性插值。
     * 这是 REGEX/SEMANTIC 等没有词级时间戳可用的命中的兜底定位手段(EXACT/VARIANT 走更精确的 SegmentIndex)。
     * 文本为空时退回段起始时间。
     *
     * @param sourceOffset 命中在原始段文本中的字符偏移
     * @return 映射出的时间轴秒数
     */
    private double mapSourceOffset(TranscriptSegment segment, int sourceOffset) {
        if (segment.getText() == null || segment.getText().isBlank()) {
            return segment.getStartTime();
        }
        double ratio = Math.min(1.0, Math.max(0, sourceOffset / (double) segment.getText().length()));
        return segment.getStartTime() + (segment.getEndTime() - segment.getStartTime()) * ratio;
    }
}
