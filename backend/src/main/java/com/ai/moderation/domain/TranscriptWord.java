package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 词级时间戳实体,对应表 {@code transcript_words}。
 * <p>从属于 {@link TranscriptSegment},记录段内每个词的精确起止时间;命中后用其时间戳把候选定位到时间轴。
 */
@Getter
@Setter
@TableName("transcript_words")
public class TranscriptWord implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外键,所属检测任务 {@link DetectionJob#getId()}。 */
    private Long jobId;

    /** 外键,所属转写段 {@link TranscriptSegment#getId()}。 */
    private Long segmentId;

    /** 词在所属段内的序号。 */
    private int sequenceNo;

    /** 原始词形(转写直出)。 */
    private String word;

    /** 归一化词形,供规则匹配使用,区别于原始 {@code word}。 */
    private String normalizedWord;

    /** 词起始时间(秒)。 */
    private double startTime;

    /** 词结束时间(秒)。 */
    private double endTime;
}
