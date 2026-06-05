package com.ai.moderation.service;

import com.ai.moderation.config.ApiType;
import com.ai.moderation.service.support.ExtractedHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiModerationClientExtractTest {
    private ObjectMapper objectMapper;
    private AiModerationClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // parseExtraction 是纯函数,不触达 SettingsService,这里用桩即可
        client = new AiModerationClient(mock(SettingsService.class), objectMapper);
    }

    /** 把任意文本包装成 chat completions 的 message.content,避免手写转义。 */
    private JsonNode chat(String content) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", content);
        return root;
    }

    @Test
    void parsesStandardHitsObject() {
        JsonNode root = chat("""
                {"hits":[{"sequenceNo":2,"matchedText":"全网最低","term":"全网最低","category":"广告极限词","severity":"CRITICAL","confidence":0.95,"reason":"绝对化用语"}]}""");

        List<ExtractedHit> hits = client.parseExtraction(root, ApiType.CHAT);

        assertThat(hits).hasSize(1);
        ExtractedHit hit = hits.getFirst();
        assertThat(hit.segmentSeq()).isEqualTo(2);
        assertThat(hit.matchedText()).isEqualTo("全网最低");
        assertThat(hit.category()).isEqualTo("广告极限词");
        assertThat(hit.severity()).isEqualTo("CRITICAL");
        assertThat(hit.confidence()).isEqualTo(0.95);
        assertThat(hit.reason()).isEqualTo("绝对化用语");
    }

    @Test
    void parsesHitsWrappedInCodeFence() {
        JsonNode root = chat("""
                ```json
                {"hits":[{"sequenceNo":0,"matchedText":"九块九","term":"价格","category":"价格","severity":"MEDIUM","confidence":0.8,"reason":"价格表达"}]}
                ```""");

        List<ExtractedHit> hits = client.parseExtraction(root, ApiType.CHAT);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().matchedText()).isEqualTo("九块九");
    }

    @Test
    void parsesHitsEmbeddedInExplanatoryText() {
        JsonNode root = chat("""
                好的,提取结果如下:{"hits":[{"sequenceNo":1,"matchedText":"包治百病","term":"包治百病","category":"功效","severity":"HIGH","confidence":0.7,"reason":"夸大功效"}]} 以上。""");

        List<ExtractedHit> hits = client.parseExtraction(root, ApiType.CHAT);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().matchedText()).isEqualTo("包治百病");
    }

    @Test
    void parsesBareArrayFallback() {
        JsonNode root = chat("""
                [{"sequenceNo":3,"matchedText":"最便宜","term":"最便宜","category":"广告极限词","severity":"HIGH","confidence":0.6,"reason":"极限词"}]""");

        List<ExtractedHit> hits = client.parseExtraction(root, ApiType.CHAT);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().segmentSeq()).isEqualTo(3);
    }

    @Test
    void returnsEmptyForNoHits() {
        JsonNode root = chat("""
                {"hits":[]}""");

        assertThat(client.parseExtraction(root, ApiType.CHAT)).isEmpty();
    }

    @Test
    void skipsItemsMissingSequenceOrMatchedText() {
        JsonNode root = chat("""
                {"hits":[
                  {"matchedText":"无段号","term":"x","category":"c","severity":"LOW","confidence":0.9,"reason":"r"},
                  {"sequenceNo":1,"matchedText":"","term":"x","category":"c","severity":"LOW","confidence":0.9,"reason":"r"},
                  {"sequenceNo":1,"matchedText":"有效","term":"x","category":"c","severity":"LOW","confidence":0.9,"reason":"r"}
                ]}""");

        List<ExtractedHit> hits = client.parseExtraction(root, ApiType.CHAT);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().matchedText()).isEqualTo("有效");
    }

    @Test
    void clampsConfidenceOutOfRange() {
        JsonNode root = chat("""
                {"hits":[{"sequenceNo":0,"matchedText":"x","term":"x","category":"c","severity":"LOW","confidence":1.8,"reason":"r"}]}""");

        List<ExtractedHit> hits = client.parseExtraction(root, ApiType.CHAT);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().confidence()).isEqualTo(1.0);
    }

    @Test
    void returnsEmptyForUnparseableContent() {
        JsonNode root = chat("抱歉,我无法完成提取。");

        assertThat(client.parseExtraction(root, ApiType.CHAT)).isEmpty();
    }
}
