package com.ai.moderation.asr;

public record TranscriptionWord(
        String word,
        double start,
        double end
) {
}

