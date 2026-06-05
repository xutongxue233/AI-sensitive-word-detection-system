package com.ai.moderation.dto;

import com.ai.moderation.domain.TranscriptWord;

/**
 * 词级时间戳响应体:转写段内单个词及其起止时间。
 *
 * <p>词级时间戳用于把命中精确定位到时间轴(命中往往只覆盖段内某几个词),
 * 是把规则/AI 召回结果落到具体时间区间的依据。
 *
 * @param id         词主键
 * @param sequenceNo 词在所属段内的顺序号
 * @param word       词文本
 * @param startTime  词起始时间(秒)
 * @param endTime    词结束时间(秒)
 */
public record TranscriptWordResponse(
        Long id,
        int sequenceNo,
        String word,
        double startTime,
        double endTime
) {
    /**
     * 从词实体投影出响应体。
     *
     * @param word 词实体
     * @return 对应的响应体
     */
    public static TranscriptWordResponse from(TranscriptWord word) {
        return new TranscriptWordResponse(word.getId(), word.getSequenceNo(), word.getWord(), word.getStartTime(), word.getEndTime());
    }
}

