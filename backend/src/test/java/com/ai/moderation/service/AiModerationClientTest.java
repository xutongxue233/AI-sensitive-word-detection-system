package com.ai.moderation.service;

import com.ai.moderation.config.ApiType;
import com.ai.moderation.service.support.AiDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

class AiModerationClientTest {
    private ObjectMapper objectMapper;
    private AiModerationClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // parseDecision 是纯函数,不触达 SettingsService,这里用桩即可
        client = new AiModerationClient(mock(SettingsService.class), objectMapper);
    }

    @Test
    void parsesChatCompletionsContent() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {"choices":[{"message":{"content":"{\\"violation\\":true,\\"confidence\\":0.92,\\"category\\":\\"广告极限词\\",\\"reason\\":\\"绝对化用语\\"}"}}]}
                """);

        AiDecision decision = client.parseDecision(root, ApiType.CHAT, "兜底分类");

        assertThat(decision.violation()).isTrue();
        assertThat(decision.confidence()).isEqualTo(0.92);
        assertThat(decision.category()).isEqualTo("广告极限词");
        assertThat(decision.reason()).isEqualTo("绝对化用语");
    }

    @Test
    void parsesChatContentWrappedInCodeFence() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {"choices":[{"message":{"content":"```json\\n{\\"violation\\":false,\\"confidence\\":0.2,\\"category\\":\\"价格\\",\\"reason\\":\\"非违规\\"}\\n```"}}]}
                """);

        AiDecision decision = client.parseDecision(root, ApiType.CHAT, "兜底分类");

        assertThat(decision.violation()).isFalse();
        assertThat(decision.confidence()).isEqualTo(0.2);
        assertThat(decision.category()).isEqualTo("价格");
    }

    @Test
    void parsesResponsesApiOutputSkippingReasoningItems() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {"output":[
                  {"type":"reasoning","content":[]},
                  {"type":"message","content":[{"type":"output_text","text":"{\\"violation\\":false,\\"confidence\\":0.3,\\"category\\":\\"价格\\",\\"reason\\":\\"上下文不构成违规\\"}"}]}
                ]}
                """);

        AiDecision decision = client.parseDecision(root, ApiType.RESPONSES, "兜底分类");

        assertThat(decision.violation()).isFalse();
        assertThat(decision.confidence()).isEqualTo(0.3);
        assertThat(decision.category()).isEqualTo("价格");
        assertThat(decision.reason()).isEqualTo("上下文不构成违规");
    }

    @Test
    void clampsConfidenceAndUsesFallbackCategoryWhenMissing() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {"output_text":"{\\"violation\\":true,\\"confidence\\":1.8}"}
                """);

        AiDecision decision = client.parseDecision(root, ApiType.RESPONSES, "兜底分类");

        assertThat(decision.violation()).isTrue();
        assertThat(decision.confidence()).isEqualTo(1.0, within(1e-9));
        assertThat(decision.category()).isEqualTo("兜底分类");
    }

    @Test
    void unparseableContentFallsBackToDefaults() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {"choices":[{"message":{"content":"抱歉，我无法判断"}}]}
                """);

        AiDecision decision = client.parseDecision(root, ApiType.CHAT, "兜底分类");

        assertThat(decision.violation()).isTrue();
        assertThat(decision.confidence()).isEqualTo(0.6, within(1e-9));
        assertThat(decision.category()).isEqualTo("兜底分类");
    }
}
