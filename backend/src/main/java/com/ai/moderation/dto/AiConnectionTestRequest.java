package com.ai.moderation.dto;

public record AiConnectionTestRequest(
        String aiApiType,
        String aiBaseUrl,
        String aiApiKey,
        String aiModel,
        Double aiTemperature,
        Integer aiTimeoutSeconds
) {
}
