package com.ai.moderation.asr;

public record TextToken(
        String text,
        String normalized,
        int sourceStart,
        int sourceEnd
) {
}

