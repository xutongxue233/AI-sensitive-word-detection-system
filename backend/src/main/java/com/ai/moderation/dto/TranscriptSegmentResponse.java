package com.ai.moderation.dto;

import com.ai.moderation.domain.TranscriptSegment;

import java.util.List;

public record TranscriptSegmentResponse(
        Long id,
        int sequenceNo,
        double startTime,
        double endTime,
        String text,
        List<TranscriptWordResponse> words
) {
    public static TranscriptSegmentResponse from(TranscriptSegment segment, List<TranscriptWordResponse> words) {
        return new TranscriptSegmentResponse(
                segment.getId(),
                segment.getSequenceNo(),
                segment.getStartTime(),
                segment.getEndTime(),
                segment.getText(),
                words
        );
    }
}

