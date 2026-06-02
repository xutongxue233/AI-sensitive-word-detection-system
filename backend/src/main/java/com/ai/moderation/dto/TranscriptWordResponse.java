package com.ai.moderation.dto;

import com.ai.moderation.domain.TranscriptWord;

public record TranscriptWordResponse(
        Long id,
        int sequenceNo,
        String word,
        double startTime,
        double endTime
) {
    public static TranscriptWordResponse from(TranscriptWord word) {
        return new TranscriptWordResponse(word.getId(), word.getSequenceNo(), word.getWord(), word.getStartTime(), word.getEndTime());
    }
}

