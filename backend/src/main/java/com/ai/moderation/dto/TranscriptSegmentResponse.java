package com.ai.moderation.dto;

import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;

import java.util.List;

/**
 * 转写段响应体:一段带时间区间的转写文本及其词级时间戳。
 *
 * <p>来源可能是音频 Whisper 转写、画面硬字幕 OCR 或外部字幕文件,由 {@code source} 区分。
 * 其中 bbox 四个归一化坐标(0~1)仅 {@link TranscriptSource#VIDEO_SUBTITLE} 来源段有值、
 * 音频段为 null,供 delogo 去字幕时定位字幕框。
 *
 * @param id         转写段主键
 * @param sequenceNo 段在转写中的顺序号
 * @param startTime  段起始时间(秒)
 * @param endTime    段结束时间(秒)
 * @param text       段文本
 * @param source     转写来源,见 {@link TranscriptSource}
 * @param bboxX      字幕框左上角 x 归一化坐标(0~1);非画面字幕段为 null
 * @param bboxY      字幕框左上角 y 归一化坐标(0~1);非画面字幕段为 null
 * @param bboxWidth  字幕框归一化宽度(0~1);非画面字幕段为 null
 * @param bboxHeight 字幕框归一化高度(0~1);非画面字幕段为 null
 * @param words      段内词级时间戳列表
 */
public record TranscriptSegmentResponse(
        Long id,
        int sequenceNo,
        double startTime,
        double endTime,
        String text,
        TranscriptSource source,
        Double bboxX,
        Double bboxY,
        Double bboxWidth,
        Double bboxHeight,
        List<TranscriptWordResponse> words
) {
    /**
     * 从转写段实体与已投影的词列表组装响应体。
     *
     * @param segment 转写段实体
     * @param words   段内词级时间戳列表(已投影为响应体)
     * @return 对应的响应体
     */
    public static TranscriptSegmentResponse from(TranscriptSegment segment, List<TranscriptWordResponse> words) {
        return new TranscriptSegmentResponse(
                segment.getId(),
                segment.getSequenceNo(),
                segment.getStartTime(),
                segment.getEndTime(),
                segment.getText(),
                segment.getSource(),
                segment.getBboxX(),
                segment.getBboxY(),
                segment.getBboxWidth(),
                segment.getBboxHeight(),
                words
        );
    }
}
