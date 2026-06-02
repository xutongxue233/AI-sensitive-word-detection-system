package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiReviewService {
    private final AiProperties properties;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;

    public AiReviewService(
            AiProperties properties,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void reviewJob(Long jobId) {
        List<TermHit> hits = hitRepository.findByJobIdOrderByStartTimeAsc(jobId);
        for (TermHit hit : hits) {
            AiDecision decision = properties.enabled() ? callAi(hit) : localFallback(hit);
            AiReview review = new AiReview();
            review.setHitId(hit.getId());
            review.setViolation(decision.violation());
            review.setConfidence(decision.confidence());
            review.setCategory(decision.category());
            review.setReason(decision.reason());
            review.setRawResponse(decision.rawResponse());
            reviewRepository.save(review);
            hit.setReviewStatus(decision.violation() ? ReviewStatus.VIOLATION : ReviewStatus.SAFE);
            hit.setAiConfidence(decision.confidence());
            hitRepository.save(hit);
        }
    }

    private AiDecision localFallback(TermHit hit) {
        String reason = "AI 未启用，系统保留规则命中结果，建议人工确认。";
        return new AiDecision(true, 0.70, hit.getCategory(), reason, reason);
    }

    private AiDecision callAi(TermHit hit) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", properties.model());
            request.put("temperature", 0);
            request.put("response_format", Map.of("type", "json_object"));
            request.put("messages", List.of(
                    Map.of(
                            "role", "system",
                            "content", "你是视频内容审核助手。只判断候选违规词在上下文中是否构成真实违规，返回严格 JSON。"
                    ),
                    Map.of(
                            "role", "user",
                            "content", """
                                    请根据上下文判断候选词是否违规。
                                    如果匹配方式是 SEMANTIC，候选词只是类型候选，不是固定违规词；请重点判断它在上下文里是否属于该分类。
                                    返回 JSON：{"violation":true|false,"confidence":0到1,"category":"分类","reason":"一句中文原因"}
                                    候选词：%s
                                    分类：%s
                                    严重级别：%s
                                    匹配方式：%s
                                    上下文：%s
                                    """.formatted(hit.getMatchedText(), hit.getCategory(), hit.getSeverity(), hit.getRuleSource(), hit.getContextText())
                    )
            ));
            RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
            if (StringUtils.hasText(properties.apiKey())) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
            }
            JsonNode response = builder.build()
                    .post()
                    .uri("/v1/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            String content = response == null ? "" : response.at("/choices/0/message/content").asText("");
            JsonNode json = objectMapper.readTree(stripCodeFence(content));
            return new AiDecision(
                    json.path("violation").asBoolean(true),
                    Math.max(0, Math.min(1, json.path("confidence").asDouble(0.6))),
                    json.path("category").asText(hit.getCategory()),
                    json.path("reason").asText("AI 已完成复核。"),
                    content
            );
        } catch (Exception ex) {
            String reason = "AI 复核失败，保留规则命中结果：" + ex.getMessage();
            return new AiDecision(true, 0.60, hit.getCategory(), reason, reason);
        }
    }

    private String stripCodeFence(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return value;
    }

    private record AiDecision(boolean violation, double confidence, String category, String reason, String rawResponse) {
    }
}
