package com.ai.moderation.asr;

import java.util.List;

public record TranscriptionSegment(
        double start,
        double end,
        String text,
        List<TranscriptionWord> words
) {
}

