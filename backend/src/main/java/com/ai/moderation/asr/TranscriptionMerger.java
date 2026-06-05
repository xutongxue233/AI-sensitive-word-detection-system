package com.ai.moderation.asr;

import com.ai.moderation.domain.TranscriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 多层转写去重合并器:把音频腿、画面 OCR 腿、外部字幕腿产出的 {@link TranscriptionResult} 归并为一份。
 *
 * <p>关键设计:仅在<b>同一来源</b>内判重——不同 {@link TranscriptSource} 即便同一时段文本相同也各保留一条,
 * 因为音频命中与字幕命中的导出方式不同(删片段 vs 去字幕),不能相互吞并。
 */
@Component
public class TranscriptionMerger {
    private final TextNormalizer textNormalizer;

    public TranscriptionMerger(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    /**
     * 合并两份结果(主、次),内部委托给 {@link #merge(List)}。
     *
     * @param primary   主结果
     * @param secondary 次结果
     * @return 跨源去重合并后的结果
     */
    public TranscriptionResult merge(TranscriptionResult primary, TranscriptionResult secondary) {
        return merge(List.of(primary, secondary));
    }

    /**
     * 跨多份结果合并:逐段累加,丢弃空白段,仅在同源内判重;最终按 start→end→source 名稳定排序。
     *
     * @param results 待合并的多份转写结果(顺序即优先级,先入者作为去重基准)
     * @return 去重并排序后的结果
     */
    public TranscriptionResult merge(List<TranscriptionResult> results) {
        List<TranscriptionSegment> merged = new ArrayList<>();
        for (TranscriptionResult result : results) {
            for (TranscriptionSegment candidate : segments(result)) {
                if (candidate.text() == null || candidate.text().isBlank()) {
                    continue;
                }
                if (!isDuplicate(candidate, merged)) {
                    merged.add(candidate);
                }
            }
        }
        merged.sort(Comparator
                .comparingDouble(TranscriptionSegment::start)
                .thenComparingDouble(TranscriptionSegment::end)
                .thenComparing(segment -> sourceKey(segment).name()));
        return new TranscriptionResult(merged);
    }

    /**
     * 同源合并:以主结果为基准追加次结果中的非重复段,不做跨源区分(调用方保证两者同源)。
     *
     * @param primary   主结果(全部保留)
     * @param secondary 次结果(逐段判重后追加)
     * @return 合并并按 start→end 排序后的结果
     */
    public TranscriptionResult mergeSameSource(TranscriptionResult primary, TranscriptionResult secondary) {
        List<TranscriptionSegment> merged = new ArrayList<>(segments(primary));
        for (TranscriptionSegment candidate : segments(secondary)) {
            if (candidate.text() == null || candidate.text().isBlank()) {
                continue;
            }
            if (!isDuplicate(candidate, merged)) {
                merged.add(candidate);
            }
        }
        merged.sort(Comparator
                .comparingDouble(TranscriptionSegment::start)
                .thenComparingDouble(TranscriptionSegment::end));
        return new TranscriptionResult(merged);
    }

    private List<TranscriptionSegment> segments(TranscriptionResult result) {
        if (result == null || result.segments() == null) {
            return List.of();
        }
        return result.segments();
    }

    /**
     * 判断候选段是否已被现有段覆盖。需同时满足三要件方判重:
     * 同源、时间重叠达阈值(见 {@link #hasEnoughOverlap})、文本相等或互相包含。
     *
     * @param candidate 候选段
     * @param existing  已收录的段集合
     * @return 视为重复返回 true(空白文本亦按重复丢弃)
     */
    private boolean isDuplicate(TranscriptionSegment candidate, List<TranscriptionSegment> existing) {
        String candidateText = normalize(candidate.text());
        if (candidateText.isBlank()) {
            return true;
        }
        for (TranscriptionSegment segment : existing) {
            if (!Objects.equals(sourceKey(candidate), sourceKey(segment))) {
                continue;
            }
            if (!hasEnoughOverlap(candidate, segment)) {
                continue;
            }
            String existingText = normalize(segment.text());
            if (existingText.isBlank()) {
                continue;
            }
            if (existingText.equals(candidateText)
                    || existingText.contains(candidateText)
                    || candidateText.contains(existingText)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 时间重叠是否足够:重叠时长占两段中较短段时长的比例 ≥ 0.5 即认定时段对齐。
     *
     * <p>较短段时长下限取 0.01 秒以防零时长段导致除零。
     *
     * @param a 段一
     * @param b 段二
     * @return 重叠比达到 0.5 阈值返回 true
     */
    private boolean hasEnoughOverlap(TranscriptionSegment a, TranscriptionSegment b) {
        double start = Math.max(a.start(), b.start());
        double end = Math.min(a.end(), b.end());
        double overlap = Math.max(0, end - start);
        double shorter = Math.max(0.01, Math.min(a.end() - a.start(), b.end() - b.start()));
        return overlap / shorter >= 0.5;
    }

    private String normalize(String text) {
        return textNormalizer.normalizeForMatch(text);
    }

    /**
     * 取段的来源用于同源判重,缺省视为 {@link TranscriptSource#AUDIO}(历史数据可能未写 source)。
     *
     * @param segment 字幕段
     * @return 段来源;为 null 时回退为 AUDIO
     */
    private TranscriptSource sourceKey(TranscriptionSegment segment) {
        return segment.source() == null
                ? TranscriptSource.AUDIO
                : segment.source();
    }
}
