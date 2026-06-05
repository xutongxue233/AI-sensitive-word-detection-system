package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 转写段实体,对应表 {@code transcript_segments}。
 * <p>承载音频 ASR 或画面 OCR 转写出的一段文本及其时间区间,是规则召回与命中定位的基本单元;
 * 含词级时间戳的 {@link TranscriptWord} 从属于本段。
 */
@Getter
@Setter
@TableName("transcript_segments")
public class TranscriptSegment implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外键,所属检测任务 {@link DetectionJob#getId()}。 */
    private Long jobId;

    /** 段序号,标识在转写结果中的先后次序。 */
    private int sequenceNo;

    /** 段起始时间(秒)。 */
    private double startTime;

    /** 段结束时间(秒)。 */
    private double endTime;

    private String text;

    /** 转写来源:区分音频 ASR 与画面 OCR,取值见 {@link TranscriptSource}。 */
    private TranscriptSource source = TranscriptSource.AUDIO;

    /** 字幕框左上角 x 坐标(归一化 0~1,非像素);仅 VIDEO_SUBTITLE 段有值,供导出 delogo 去字幕定位。 */
    private Double bboxX;

    /** 字幕框左上角 y 坐标(归一化 0~1,非像素);仅 VIDEO_SUBTITLE 段有值。 */
    private Double bboxY;

    /** 字幕框宽度(归一化 0~1,非像素);仅 VIDEO_SUBTITLE 段有值。 */
    private Double bboxWidth;

    /** 字幕框高度(归一化 0~1,非像素);仅 VIDEO_SUBTITLE 段有值。 */
    private Double bboxHeight;
}
