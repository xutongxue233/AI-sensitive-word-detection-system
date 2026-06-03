package com.ai.moderation.asr;

import com.ai.moderation.domain.TranscriptSource;

import java.util.List;

public record TranscriptionSegment(
        double start,
        double end,
        String text,
        List<TranscriptionWord> words,
        TranscriptSource source,
        Double bboxX,
        Double bboxY,
        Double bboxWidth,
        Double bboxHeight
) {
    public TranscriptionSegment(double start, double end, String text, List<TranscriptionWord> words) {
        this(start, end, text, words, TranscriptSource.AUDIO, null, null, null, null);
    }

    public TranscriptionSegment withSource(TranscriptSource source) {
        return new TranscriptionSegment(start, end, text, words, source, bboxX, bboxY, bboxWidth, bboxHeight);
    }
}
