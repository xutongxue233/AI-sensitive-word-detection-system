package com.ai.moderation.dto;

public record AiConnectionTestResponse(
        boolean ok,
        String message,
        String model,
        long elapsedMs
) {
}
