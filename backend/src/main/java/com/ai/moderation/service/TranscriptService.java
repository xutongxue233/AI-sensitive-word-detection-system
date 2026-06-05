package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.asr.TextToken;
import com.ai.moderation.asr.TranscriptionResult;
import com.ai.moderation.asr.TranscriptionSegment;
import com.ai.moderation.asr.TranscriptionWord;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.dto.TranscriptSegmentResponse;
import com.ai.moderation.dto.TranscriptWordResponse;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 转写落库服务:TRANSCRIBING 阶段把合并后的 {@link TranscriptionResult}
 * 持久化为段({@link TranscriptSegment})+ 词({@link TranscriptWord}),供后续规则匹配与时间轴定位使用。
 *
 * <p>段保留 source/bbox(区分音频转写与画面字幕,字幕命中导出走 delogo 去字幕路径);
 * 词文本经 {@link TextNormalizer} 归一化以便规则匹配,并携带词级时间戳用于精确定位剪辑区间。
 */
@Service
public class TranscriptService {
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptWordRepository wordRepository;
    private final TextNormalizer textNormalizer;

    public TranscriptService(
            TranscriptSegmentRepository segmentRepository,
            TranscriptWordRepository wordRepository,
            TextNormalizer textNormalizer
    ) {
        this.segmentRepository = segmentRepository;
        this.wordRepository = wordRepository;
        this.textNormalizer = textNormalizer;
    }

    /**
     * 幂等替换某任务的全部转写结果:先删该 job 的旧段/旧词,再整批写入新结果。
     *
     * <p>逐段保留 source 与 bbox(source 缺省按 {@link TranscriptSource#AUDIO} 处理);
     * 每个词经 {@link TextNormalizer#normalizeForMatch} 归一化,归一化后为空白(纯标点/空格)
     * 则跳过该词不入库——这类 token 不参与规则匹配,留下只会污染词序与索引。
     *
     * @param job    所属检测任务
     * @param result 合并去重后的转写结果(段含可选词级时间戳)
     */
    @Transactional
    public void replaceTranscript(DetectionJob job, TranscriptionResult result) {
        wordRepository.deleteByJobId(job.getId());
        segmentRepository.deleteByJobId(job.getId());
        int segmentIndex = 0;
        int wordIndex = 0;
        for (TranscriptionSegment item : result.segments()) {
            TranscriptSegment segment = new TranscriptSegment();
            segment.setJobId(job.getId());
            segment.setSequenceNo(segmentIndex++);
            segment.setStartTime(item.start());
            segment.setEndTime(item.end());
            segment.setText(item.text() == null ? "" : item.text());
            segment.setSource(item.source() == null ? TranscriptSource.AUDIO : item.source());
            segment.setBboxX(item.bboxX());
            segment.setBboxY(item.bboxY());
            segment.setBboxWidth(item.bboxWidth());
            segment.setBboxHeight(item.bboxHeight());
            TranscriptSegment savedSegment = segmentRepository.save(segment);

            List<TranscriptWord> words = new ArrayList<>();
            // 段自带词级时间戳(Whisper word timestamps)时直接用;字幕/缺词级时间戳时按 token 均分段时长兜底。
            List<TranscriptionWord> sourceWords = item.words() == null || item.words().isEmpty()
                    ? inferWords(segment.getText(), item.start(), item.end())
                    : item.words();
            for (TranscriptionWord sourceWord : sourceWords) {
                String normalized = textNormalizer.normalizeForMatch(sourceWord.word());
                if (normalized.isBlank()) {
                    continue;
                }
                TranscriptWord word = new TranscriptWord();
                word.setJobId(job.getId());
                word.setSegmentId(savedSegment.getId());
                word.setSequenceNo(wordIndex++);
                word.setWord(sourceWord.word().trim());
                word.setNormalizedWord(normalized);
                word.setStartTime(sourceWord.start());
                word.setEndTime(sourceWord.end());
                words.add(word);
            }
            wordRepository.saveAll(words);
        }
    }

    /**
     * 无词级时间戳时,把段文本切 token 并在段时长内均分,推断每个词的起止时间。
     *
     * <p>末词的 end 直接对齐段 end(而非累计 start+step*n),避免浮点累计误差让最后一词越过段边界;
     * duration 用 {@code Math.max(0.01, end-start)} 兜底,防止零时长段导致除零。
     *
     * @param text  段文本
     * @param start 段起始时间(秒)
     * @param end   段结束时间(秒)
     * @return 推断出的带时间戳词列表;无可切 token 时返回空列表
     */
    private List<TranscriptionWord> inferWords(String text, double start, double end) {
        List<TextToken> tokens = textNormalizer.tokenize(text);
        if (tokens.isEmpty()) {
            return List.of();
        }
        double duration = Math.max(0.01, end - start);
        double step = duration / tokens.size();
        List<TranscriptionWord> words = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            double wordStart = start + step * i;
            double wordEnd = i == tokens.size() - 1 ? end : start + step * (i + 1);
            words.add(new TranscriptionWord(tokens.get(i).text(), wordStart, wordEnd));
        }
        return words;
    }

    /**
     * 读取某任务的全部转写段及其归属词,组装为前端展示用的响应。
     * 一次性按段分组所有词,避免逐段查询词表。
     *
     * @param jobId 检测任务 id
     * @return 按段序排列、每段挂载其词列表的响应
     */
    @Transactional(readOnly = true)
    public List<TranscriptSegmentResponse> listSegments(Long jobId) {
        List<TranscriptSegment> segments = segmentRepository.findByJobIdOrderBySequenceNoAsc(jobId);
        Map<Long, List<TranscriptWordResponse>> wordsBySegment = wordRepository.findByJobIdOrderBySequenceNoAsc(jobId)
                .stream()
                .collect(Collectors.groupingBy(
                        TranscriptWord::getSegmentId,
                        Collectors.mapping(TranscriptWordResponse::from, Collectors.toList())
                ));
        return segments.stream()
                .map(segment -> TranscriptSegmentResponse.from(segment, wordsBySegment.getOrDefault(segment.getId(), List.of())))
                .toList();
    }
}
