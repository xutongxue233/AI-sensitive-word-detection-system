package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.ApiType;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.dto.AiConnectionTestRequest;
import com.ai.moderation.dto.AiConnectionTestResponse;
import com.ai.moderation.dto.GeneratedTermResponse;
import com.ai.moderation.service.support.AiDecision;
import com.ai.moderation.service.support.ExtractedHit;
import com.ai.moderation.service.support.FormatMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用 OpenAI 兼容接口对单条命中做内容复核。
 * 支持两种形态(由运行时设置 app.ai.api-type / 前台「系统设置」决定):
 * - CHAT:      POST /v1/chat/completions, response_format=json_schema
 * - RESPONSES: POST /v1/responses,        text.format=json_schema
 * 两种形态都强制返回 {violation, confidence, category, reason} 的严格 JSON。
 * 端点/密钥/模型/超时等可在运行时变更,故每次复核按当前设置临时构建 RestClient。
 */
@Component
public class AiModerationClient {
    private static final Logger log = LoggerFactory.getLogger(AiModerationClient.class);
    private static final String SCHEMA_NAME = "moderation_decision";
    private static final String EXTRACTION_SCHEMA_NAME = "moderation_extraction";
    private static final String TERM_GENERATION_SCHEMA_NAME = "term_generation";

    // 记忆当前端点已探测出的可用档位,避免每条命中都从 json_schema 重试。
    // key 由 apiType|baseUrl|model 组成,设置变更时重新探测。
    private volatile FormatMode cachedMode = FormatMode.JSON_SCHEMA;
    private volatile String cachedModeKey = "";
    private final Set<String> reportedFallbacks = ConcurrentHashMap.newKeySet();

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public AiModerationClient(SettingsService settingsService, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    public AiDecision review(TermHit hit) {
        AiProperties config = settingsService.currentAi();
        RestClient client = buildClient(config);
        String systemPrompt = systemPrompt();
        String userPrompt = userPrompt(hit);
        ApiType type = config.apiType();
        JsonNode response = callWithFallback(client, config, type, systemPrompt, userPrompt,
                decisionSchema(), SCHEMA_NAME);
        return parseDecision(response, type, hit.getCategory());
    }

    /**
     * 通读整篇字幕 + 全量词库,一次性提取所有命中敏感词库的片段。
     * 走与单条复核相同的端点与结构化输出降级机制,只是 schema 换成数组形态。
     */
    public List<ExtractedHit> extract(List<TranscriptSegment> segments, List<ViolationTerm> terms) {
        AiProperties config = settingsService.currentAi();
        RestClient client = buildClient(config);
        String systemPrompt = extractionSystemPrompt();
        String userPrompt = extractionUserPrompt(segments, terms);
        ApiType type = config.apiType();
        JsonNode response = callWithFallback(client, config, type, systemPrompt, userPrompt,
                extractionSchema(), EXTRACTION_SCHEMA_NAME);
        return parseExtraction(response, type);
    }

    /**
     * 按自然语言需求生成违规词库候选项。这里只生成候选,不做入库。
     */
    public List<GeneratedTermResponse> generateTerms(String prompt, String category, int count, List<ViolationTerm> existingTerms) {
        AiProperties config = settingsService.currentAi();
        if (!StringUtils.hasText(config.baseUrl())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI Base URL 不能为空");
        }
        if (!StringUtils.hasText(config.model())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI 模型名称不能为空");
        }
        RestClient client = buildClient(config);
        ApiType type = config.apiType();
        JsonNode response = callWithFallback(
                client,
                config,
                type,
                termGenerationSystemPrompt(),
                termGenerationUserPrompt(prompt, category, count, existingTerms),
                termGenerationSchema(),
                TERM_GENERATION_SCHEMA_NAME
        );
        return parseGeneratedTerms(response, type);
    }

    /**
     * 按当前端点已知的兼容档位发起调用;若因结构化输出不被支持而 400,
     * 自动降级(json_schema -> json_object -> none)重试并记忆,使后续调用直接命中可用档位。
     */
    private JsonNode callWithFallback(RestClient client, AiProperties config, ApiType type,
                                      String systemPrompt, String userPrompt,
                                      Map<String, Object> schema, String schemaName) {
        FormatMode mode = resolveMode(config);
        while (true) {
            try {
                JsonNode response = type == ApiType.RESPONSES
                        ? callResponses(client, config, systemPrompt, userPrompt, mode, schema, schemaName)
                        : callChat(client, config, systemPrompt, userPrompt, mode, schema, schemaName);
                rememberMode(config, mode);
                return response;
            } catch (RestClientResponseException ex) {
                FormatMode next = nextMode(mode, ex);
                if (next == null) {
                    throw ex;
                }
                rememberMode(config, next);
                logFormatFallbackOnce(config, mode, next, ex.getStatusCode().value());
                mode = next;
            }
        }
    }

    private void logFormatFallbackOnce(AiProperties config, FormatMode from, FormatMode to, int statusCode) {
        String key = formatKey(config) + "|" + from + "->" + to;
        if (reportedFallbacks.add(key)) {
            log.warn("结构化输出档位 {} 不被端点支持(HTTP {}),已降级为 {}。同一端点后续将直接使用该档位", from, statusCode, to);
        } else {
            log.debug("结构化输出档位 {} 不被端点支持(HTTP {}),继续使用已缓存档位 {}", from, statusCode, to);
        }
    }

    public AiConnectionTestResponse testConnection(AiConnectionTestRequest request) {
        AiProperties config = mergeTestConfig(request);
        if (!StringUtils.hasText(config.baseUrl())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Base URL 不能为空");
        }
        if (!StringUtils.hasText(config.model())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "模型名称不能为空");
        }

        RestClient client = buildClient(config);
        long start = System.nanoTime();
        try {
            JsonNode response = config.apiType() == ApiType.RESPONSES
                    ? callResponsesPing(client, config)
                    : callChatPing(client, config);
            String content = config.apiType() == ApiType.RESPONSES
                    ? extractResponsesContent(response)
                    : extractChatContent(response);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            String suffix = StringUtils.hasText(content) ? "，响应：" + trimForMessage(content) : "";
            return new AiConnectionTestResponse(true, "模型连通性正常" + suffix, config.model(), elapsedMs);
        } catch (RestClientResponseException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "模型接口返回 HTTP "
                    + ex.getStatusCode().value() + ": " + trimForMessage(ex.getResponseBodyAsString()));
        } catch (RestClientException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "模型接口调用失败: " + ex.getMessage());
        }
    }

    private AiProperties mergeTestConfig(AiConnectionTestRequest request) {
        AiProperties current = settingsService.currentAi();
        if (request == null) {
            return current;
        }
        return new AiProperties(
                true,
                parseApiType(StringUtils.hasText(request.aiApiType()) ? request.aiApiType() : current.apiType().name()),
                StringUtils.hasText(request.aiBaseUrl()) ? request.aiBaseUrl().trim() : current.baseUrl(),
                StringUtils.hasText(request.aiApiKey()) ? request.aiApiKey().trim() : current.apiKey(),
                StringUtils.hasText(request.aiModel()) ? request.aiModel().trim() : current.model(),
                request.aiTemperature() == null ? current.temperature() : request.aiTemperature(),
                current.confidenceThreshold(),
                request.aiTimeoutSeconds() == null ? current.timeoutSeconds() : Math.max(1, request.aiTimeoutSeconds())
        );
    }

    private JsonNode callChatPing(RestClient client, AiProperties config) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", config.model());
        applyTemperature(request, config);
        request.put("max_tokens", 16);
        request.put("messages", List.of(
                Map.of("role", "user", "content", "请只回复 OK，用于接口连通性测试。")
        ));
        return client.post()
                .uri("/v1/chat/completions")
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode callResponsesPing(RestClient client, AiProperties config) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", config.model());
        applyTemperature(request, config);
        request.put("max_output_tokens", 16);
        request.put("input", "请只回复 OK，用于接口连通性测试。");
        return client.post()
                .uri("/v1/responses")
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private ApiType parseApiType(String value) {
        if (value == null) {
            return ApiType.CHAT;
        }
        try {
            return ApiType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ApiType.CHAT;
        }
    }

    private static RestClient buildClient(AiProperties config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, config.timeoutSeconds()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(config.baseUrl() == null ? "" : config.baseUrl())
                .requestFactory(factory);
        if (StringUtils.hasText(config.apiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        return builder.build();
    }

    private JsonNode callChat(RestClient client, AiProperties config, String systemPrompt, String userPrompt,
                              FormatMode mode, Map<String, Object> schema, String schemaName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", config.model());
        applyTemperature(request, config);
        applyChatFormat(request, mode, schema, schemaName);
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return client.post()
                .uri("/v1/chat/completions")
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private void applyChatFormat(Map<String, Object> request, FormatMode mode, Map<String, Object> schema, String schemaName) {
        switch (mode) {
            case JSON_SCHEMA -> request.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", schemaName,
                            "strict", true,
                            "schema", schema
                    )
            ));
            case JSON_OBJECT -> request.put("response_format", Map.of("type", "json_object"));
            case NONE -> {
                // 不下发 response_format,纯靠 prompt 约束 + 容错解析
            }
        }
    }

    private JsonNode callResponses(RestClient client, AiProperties config, String systemPrompt, String userPrompt,
                                   FormatMode mode, Map<String, Object> schema, String schemaName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", config.model());
        applyTemperature(request, config);
        request.put("instructions", systemPrompt);
        request.put("input", userPrompt);
        applyResponsesFormat(request, mode, schema, schemaName);
        return client.post()
                .uri("/v1/responses")
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private void applyResponsesFormat(Map<String, Object> request, FormatMode mode, Map<String, Object> schema, String schemaName) {
        switch (mode) {
            case JSON_SCHEMA -> request.put("text", Map.of(
                    "format", Map.of(
                            "type", "json_schema",
                            "name", schemaName,
                            "strict", true,
                            "schema", schema
                    )
            ));
            case JSON_OBJECT -> request.put("text", Map.of(
                    "format", Map.of("type", "json_object")
            ));
            case NONE -> {
                // 不下发 text.format,纯靠 prompt 约束 + 容错解析
            }
        }
    }

    /**
     * temperature 默认随请求发送;设为负数则不下发,交由模型默认值(便于使用不支持自定义温度的推理模型)。
     */
    private void applyTemperature(Map<String, Object> request, AiProperties config) {
        if (config.temperature() >= 0) {
            request.put("temperature", config.temperature());
        }
    }

    /**
     * 判断这次 400 是否由结构化输出参数(response_format/text.format)不被支持引起。
     * 命中则可降级重试;其他 400(鉴权、模型名错误等)直接上抛,避免无意义重试。
     */
    private boolean isFormatUnsupported(RestClientResponseException ex) {
        if (ex.getStatusCode().value() != 400) {
            return false;
        }
        String body = ex.getResponseBodyAsString().toLowerCase();
        return body.contains("response_format")
                || body.contains("json_schema")
                || body.contains("text.format")
                || body.contains("unavailable")
                || (body.contains("format") && (body.contains("support") || body.contains("invalid")));
    }

    private FormatMode nextMode(FormatMode mode, RestClientResponseException ex) {
        if (!isFormatUnsupported(ex)) {
            return null;
        }
        return switch (mode) {
            case JSON_SCHEMA -> FormatMode.JSON_OBJECT;
            case JSON_OBJECT -> FormatMode.NONE;
            case NONE -> null;
        };
    }

    private FormatMode resolveMode(AiProperties config) {
        String key = formatKey(config);
        if (!key.equals(cachedModeKey)) {
            synchronized (this) {
                if (!key.equals(cachedModeKey)) {
                    cachedModeKey = key;
                    cachedMode = FormatMode.JSON_SCHEMA;
                }
            }
        }
        return cachedMode;
    }

    private void rememberMode(AiProperties config, FormatMode mode) {
        cachedModeKey = formatKey(config);
        cachedMode = mode;
    }

    private String formatKey(AiProperties config) {
        return config.apiType() + "|" + config.baseUrl() + "|" + config.model();
    }

    /**
     * 纯函数:从 AI 原始响应中抽取决策 JSON 并解析,便于单元测试。
     */
    public AiDecision parseDecision(JsonNode root, ApiType type, String fallbackCategory) {
        String content = type == ApiType.RESPONSES ? extractResponsesContent(root) : extractChatContent(root);
        JsonNode json = readJsonLenient(content);
        boolean violation = json.path("violation").asBoolean(true);
        double confidence = Math.max(0, Math.min(1, json.path("confidence").asDouble(0.6)));
        String category = json.path("category").asText(fallbackCategory == null ? "" : fallbackCategory);
        String reason = json.path("reason").asText("AI 已完成复核。");
        return new AiDecision(violation, confidence, category, reason, content);
    }

    /**
     * 宽容解析 AI 返回内容为决策 JSON。
     * NONE 档(端点不支持结构化输出)下模型常夹带解释文字或代码块,
     * 因此先去掉 ``` 围栏直接解析,失败再抽取第一个大括号平衡的 {...} 子串重试。
     * 全部失败才回退空对象,并打 warn 日志,避免置信度静默落到默认值且无从排查。
     */
    private JsonNode readJsonLenient(String content) {
        String stripped = stripCodeFence(content);
        JsonNode node = tryParseObject(stripped);
        if (node != null) {
            return node;
        }
        String extracted = extractJsonObject(stripped);
        if (extracted != null) {
            node = tryParseObject(extracted);
            if (node != null) {
                return node;
            }
        }
        log.warn("AI 返回内容无法解析为决策 JSON,回退默认置信度。原始内容: {}", trimForMessage(content));
        return objectMapper.createObjectNode();
    }

    private JsonNode tryParseObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从可能夹带解释文字的内容中抽取第一个大括号平衡的 JSON 对象子串。
     * 扫描时跳过字符串字面量内的大括号,避免被 reason 文本里的符号干扰。
     */
    private String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String extractChatContent(JsonNode root) {
        if (root == null) {
            return "";
        }
        String content = root.at("/choices/0/message/content").asText("");
        if (StringUtils.hasText(content)) {
            return content;
        }
        return root.path("output_text").asText("");
    }

    private String extractResponsesContent(JsonNode root) {
        if (root == null) {
            return "";
        }
        String direct = root.path("output_text").asText("");
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        JsonNode output = root.path("output");
        if (output.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : output) {
                if (!"message".equals(item.path("type").asText())) {
                    continue;
                }
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText()) || part.has("text")) {
                        builder.append(part.path("text").asText(""));
                    }
                }
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }
        // 兜底:部分网关把 Responses 路由成 chat 形态
        return root.at("/choices/0/message/content").asText("");
    }

    private String stripCodeFence(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return value;
    }

    private String trimForMessage(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() > 220 ? text.substring(0, 220) + "..." : text;
    }

    private static Map<String, Object> decisionSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("violation", Map.of("type", "boolean"));
        properties.put("confidence", Map.of("type", "number"));
        properties.put("category", Map.of("type", "string"));
        properties.put("reason", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("violation", "confidence", "category", "reason"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private String systemPrompt() {
        return """
                你是敏感词命中复核助手。系统已用规则从文案中召回了候选词，你唯一的任务是判断这个候选词在给定上下文中\
                是否真实命中了用户的敏感词库——即它是否确实就是这个敏感词，或确实属于这个敏感分类。
                这是“是否命中敏感词”的判断，不是“是否合法合规、是否构成违规”的判断。只要候选在上下文中确实是该敏感词\
                或属于该敏感分类，就一律算命中（violation=true），不要管它在现实中是否合法、是否属于正常营销、是否构成\
                虚假或误导——这类合规性结论一概不要做，也不要据此放过命中。
                仅在以下两种情况判为未命中（violation=false）：一是同形异义或子串误切，即候选指代的事物与该敏感词\
                完全无关（例如敏感词“上火”却出现在“上火车”中）；二是 SEMANTIC 分类候选在上下文中明显不属于该分类。
                必须只返回一个 JSON 对象，不要输出任何解释文字、Markdown 代码块或多余字符。""";
    }

    private String userPrompt(TermHit hit) {
        return """
                判断下面的候选词在上下文中是否命中敏感词库。
                命中标准是“它是否就是该敏感词 / 是否属于该敏感分类”，不是“是否违法违规”。\
                不要以“属于正常营销、价格合理、未表明虚假或误导、不构成违规”等合规理由判为未命中。
                若匹配方式是 SEMANTIC：候选词只是分类候选，请判断这段上下文是否属于该分类\
                （例如分类为“价格”时，出现“0.01元”“九块九”等任何价格表达即属于命中）。
                若匹配方式是 EXACT / VARIANT / REGEX：候选词已字面出现，除非属于同形异义或子串误切，否则即为命中。
                只输出一个 JSON 对象，不要包含任何解释文字或代码块，格式为：
                {"violation":true|false,"confidence":0到1的小数,"category":"分类","reason":"一句中文原因"}
                其中 violation 表示是否命中敏感词库（命中为 true）；\
                confidence 是你对该“是否命中”判断的真实置信度（0 到 1 之间的小数），不要恒定输出同一个值；\
                reason 用一句中文说明命中或未命中该敏感词/分类的依据，只描述命中关系，不要给出合规或违法结论。
                候选词：%s
                分类：%s
                严重级别：%s
                匹配方式：%s
                上下文：%s
                """.formatted(
                hit.getMatchedText(),
                hit.getCategory(),
                hit.getSeverity(),
                hit.getRuleSource(),
                hit.getContextText()
        );
    }

    private String extractionSystemPrompt() {
        return """
                你是敏感词命中提取助手。下面会给你一段视频字幕(按段编号并附时间)和一份敏感词库。
                你唯一的任务是逐段扫描字幕,找出所有命中敏感词库的片段——即文本中确实就是某个敏感词,\
                或确实属于某个敏感分类(含同义、变体、谐音,以及 ASR 转写错字造成的近音误写)的地方。
                这是“是否命中敏感词”的判断,不是“是否合法合规、是否构成违规”的判断。只要片段在上下文中\
                确实是该敏感词或属于该分类,就一律算命中,不要管它现实中是否合法、是否属于正常营销、是否\
                虚假或误导——这类合规性结论一概不要做,也不要据此漏报。
                仅在两种情况不算命中:一是同形异义或子串误切(例如敏感词“上火”出现在“上火车”中);\
                二是分类候选在上下文中明显不属于该分类。
                特别地,对“价格/金额/售价”这类语义分类,口语化、约数或不带“元/块”单位的价格表达也算命中,\
                例如“60几”“六十几”“几十块”“一百多”“两三百”“才二十”“到手一百八”“大几千”,\
                不要因为是约数或缺少“元/块”单位就漏掉。
                对每个命中,必须给出它所在段的 sequenceNo、命中的原文子串 matchedText(必须是该段原文里\
                逐字连续出现的子串,便于定位)、命中的词库 term、该 term 的 category 与 severity、\
                你对“是否命中”的真实置信度 confidence(0 到 1 的小数,不要恒定输出同一个值)、一句中文 reason。
                必须只返回一个 JSON 对象 {"hits":[...]},不要输出任何解释文字、Markdown 代码块或多余字符;\
                没有任何命中时返回 {"hits":[]}。""";
    }

    private String extractionUserPrompt(List<TranscriptSegment> segments, List<ViolationTerm> terms) {
        StringBuilder sb = new StringBuilder();
        sb.append("敏感词库(全量,逐条对照):\n");
        for (ViolationTerm term : terms) {
            sb.append("- term=").append(nullToEmpty(term.getTerm()))
                    .append(" | category=").append(nullToEmpty(term.getCategory()))
                    .append(" | severity=").append(term.getSeverity())
                    .append(" | matchType=").append(term.getMatchType());
            if (StringUtils.hasText(term.getVariants())) {
                sb.append(" | variants=").append(term.getVariants().replaceAll("\\s+", " ").trim());
            }
            sb.append('\n');
        }
        sb.append("\n字幕(每行: [#段号] (起-止秒) 文本):\n");
        for (TranscriptSegment segment : segments) {
            sb.append("[#").append(segment.getSequenceNo()).append("] (")
                    .append(formatSeconds(segment.getStartTime())).append('-')
                    .append(formatSeconds(segment.getEndTime())).append(") ")
                    .append(nullToEmpty(segment.getText())).append('\n');
        }
        sb.append("""

                请逐段提取所有命中敏感词库的片段,严格只返回一个 JSON 对象:
                {"hits":[{"sequenceNo":整数段号,"matchedText":"该段原文里逐字连续出现的命中子串","term":"命中的词库词","category":"分类","severity":"严重级别","confidence":0到1的小数,"reason":"一句中文命中依据"}]}
                matchedText 必须是对应段原文中真实存在的连续子串;没有任何命中时返回 {"hits":[]}。""");
        return sb.toString();
    }

    /**
     * 纯函数:从 AI 原始响应中抽取命中数组并解析为 ExtractedHit 列表,便于单元测试。
     * 兼容 {"hits":[...]} 对象、夹带解释文字/代码块,以及模型直接返回裸数组 [...] 的情况。
     * sequenceNo 缺失或 matchedText 为空的条目直接跳过,避免写入无法定位的脏命中。
     */
    public List<ExtractedHit> parseExtraction(JsonNode root, ApiType type) {
        String content = type == ApiType.RESPONSES ? extractResponsesContent(root) : extractChatContent(root);
        JsonNode hitsNode = extractHitsArray(content);
        List<ExtractedHit> hits = new ArrayList<>();
        if (hitsNode == null || !hitsNode.isArray()) {
            return hits;
        }
        for (JsonNode item : hitsNode) {
            if (!item.isObject()) {
                continue;
            }
            int sequenceNo = item.path("sequenceNo").asInt(-1);
            String matchedText = item.path("matchedText").asText("");
            if (sequenceNo < 0 || matchedText.isBlank()) {
                continue;
            }
            double confidence = Math.max(0, Math.min(1, item.path("confidence").asDouble(0.6)));
            hits.add(new ExtractedHit(
                    sequenceNo,
                    matchedText,
                    item.path("term").asText(""),
                    item.path("category").asText(""),
                    item.path("severity").asText(""),
                    confidence,
                    item.path("reason").asText("AI 已提取命中。")
            ));
        }
        return hits;
    }

    /**
     * 纯函数:从 AI 原始响应中抽取词库候选数组并解析,便于单元测试。
     */
    public List<GeneratedTermResponse> parseGeneratedTerms(JsonNode root, ApiType type) {
        String content = type == ApiType.RESPONSES ? extractResponsesContent(root) : extractChatContent(root);
        JsonNode termsNode = extractTermsArray(content);
        List<GeneratedTermResponse> terms = new ArrayList<>();
        if (termsNode == null || !termsNode.isArray()) {
            return terms;
        }
        for (JsonNode item : termsNode) {
            if (!item.isObject()) {
                continue;
            }
            String term = item.path("term").asText("").trim();
            if (term.isBlank()) {
                continue;
            }
            terms.add(new GeneratedTermResponse(
                    term,
                    item.path("category").asText("").trim(),
                    parseSeverity(item.path("severity").asText("")),
                    parseMatchType(item.path("matchType").asText("")),
                    textOrArray(item.path("variants")),
                    item.path("reason").asText("AI 生成候选词。").trim()
            ));
        }
        return terms;
    }

    /**
     * 先按 {"hits":[...]} 对象解析;失败则兜底尝试模型直接返回的裸数组 [...]。
     * 全部失败仅记 warn 并返回 null(本批跳过),不静默吞掉。
     */
    private JsonNode extractHitsArray(String content) {
        String stripped = stripCodeFence(content);
        JsonNode object = tryParseObject(stripped);
        if (object == null) {
            String extracted = extractJsonObject(stripped);
            if (extracted != null) {
                object = tryParseObject(extracted);
            }
        }
        if (object != null && object.path("hits").isArray()) {
            return object.path("hits");
        }
        JsonNode array = tryParseArray(stripped);
        if (array != null) {
            return array;
        }
        String extractedArray = extractJsonArray(stripped);
        if (extractedArray != null) {
            array = tryParseArray(extractedArray);
            if (array != null) {
                return array;
            }
        }
        log.warn("AI 提取返回内容无法解析为 hits 数组,本批跳过。原始内容: {}", trimForMessage(content));
        return null;
    }

    private JsonNode extractTermsArray(String content) {
        String stripped = stripCodeFence(content);
        JsonNode object = tryParseObject(stripped);
        if (object == null) {
            String extracted = extractJsonObject(stripped);
            if (extracted != null) {
                object = tryParseObject(extracted);
            }
        }
        if (object != null && object.path("terms").isArray()) {
            return object.path("terms");
        }
        JsonNode array = tryParseArray(stripped);
        if (array != null) {
            return array;
        }
        String extractedArray = extractJsonArray(stripped);
        if (extractedArray != null) {
            array = tryParseArray(extractedArray);
            if (array != null) {
                return array;
            }
        }
        log.warn("AI 词库生成返回内容无法解析为 terms 数组,本次结果为空。原始内容: {}", trimForMessage(content));
        return null;
    }

    private JsonNode tryParseArray(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node != null && node.isArray() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从可能夹带解释文字的内容中抽取第一个方括号平衡的 JSON 数组子串,跳过字符串字面量内的括号。
     */
    private String extractJsonArray(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('[');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> extractionSchema() {
        Map<String, Object> hitProperties = new LinkedHashMap<>();
        hitProperties.put("sequenceNo", Map.of("type", "integer"));
        hitProperties.put("matchedText", Map.of("type", "string"));
        hitProperties.put("term", Map.of("type", "string"));
        hitProperties.put("category", Map.of("type", "string"));
        hitProperties.put("severity", Map.of("type", "string"));
        hitProperties.put("confidence", Map.of("type", "number"));
        hitProperties.put("reason", Map.of("type", "string"));
        Map<String, Object> hitSchema = new LinkedHashMap<>();
        hitSchema.put("type", "object");
        hitSchema.put("properties", hitProperties);
        hitSchema.put("required", List.of("sequenceNo", "matchedText", "term", "category", "severity", "confidence", "reason"));
        hitSchema.put("additionalProperties", false);

        Map<String, Object> hitsArray = new LinkedHashMap<>();
        hitsArray.put("type", "array");
        hitsArray.put("items", hitSchema);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hits", hitsArray);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("hits"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private String termGenerationSystemPrompt() {
        return """
                你是视频内容审核系统的词库生成助手。用户会用自然语言描述需要覆盖的违规类型或场景。
                你的任务是生成可用于规则召回的词库候选,而不是给出合规解释。
                每个候选必须包含 term、category、severity、matchType、variants、reason。
                生成原则:
                1. term 要短、可直接匹配,避免整句长文案;
                2. category 使用用户要求或你归纳出的中文分类名;
                3. severity 只能是 LOW、MEDIUM、HIGH、CRITICAL;
                4. matchType 只能是 EXACT、VARIANT、REGEX、SEMANTIC。普通词用 EXACT,同义/谐音/错别字多的用 VARIANT,
                   模式表达用 REGEX,宽泛语义类别用 SEMANTIC;
                5. variants 用中文顿号分隔,没有则返回空字符串;
                6. 不要生成已经存在的词条,不要输出解释文字或 Markdown。
                必须只返回一个 JSON 对象 {"terms":[...]}。""";
    }

    private String termGenerationUserPrompt(String prompt, String category, int count, List<ViolationTerm> existingTerms) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求:\n").append(prompt).append("\n\n");
        if (StringUtils.hasText(category)) {
            sb.append("目标分类提示: ").append(category).append("\n\n");
        }
        sb.append("期望数量: ").append(Math.max(1, Math.min(50, count))).append("\n\n");
        sb.append("已有词库,请避免重复:\n");
        for (ViolationTerm term : existingTerms == null ? List.<ViolationTerm>of() : existingTerms) {
            sb.append("- ").append(nullToEmpty(term.getTerm()))
                    .append(" | ").append(nullToEmpty(term.getCategory()))
                    .append(" | ").append(term.getMatchType())
                    .append('\n');
        }
        sb.append("""

                请生成词库候选,严格只返回:
                {"terms":[{"term":"词条","category":"分类","severity":"MEDIUM","matchType":"EXACT","variants":"变体1、变体2","reason":"一句中文生成依据"}]}
                """);
        return sb.toString();
    }

    private static Map<String, Object> termGenerationSchema() {
        Map<String, Object> termProperties = new LinkedHashMap<>();
        termProperties.put("term", Map.of("type", "string"));
        termProperties.put("category", Map.of("type", "string"));
        termProperties.put("severity", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")));
        termProperties.put("matchType", Map.of("type", "string", "enum", List.of("EXACT", "VARIANT", "REGEX", "SEMANTIC")));
        termProperties.put("variants", Map.of("type", "string"));
        termProperties.put("reason", Map.of("type", "string"));
        Map<String, Object> termSchema = new LinkedHashMap<>();
        termSchema.put("type", "object");
        termSchema.put("properties", termProperties);
        termSchema.put("required", List.of("term", "category", "severity", "matchType", "variants", "reason"));
        termSchema.put("additionalProperties", false);

        Map<String, Object> termsArray = new LinkedHashMap<>();
        termsArray.put("type", "array");
        termsArray.put("items", termSchema);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("terms", termsArray);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("terms"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Severity.MEDIUM;
        }
    }

    private MatchType parseMatchType(String value) {
        try {
            return MatchType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return MatchType.EXACT;
        }
    }

    private String textOrArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return String.join("、", values);
        }
        return node.asText("").trim();
    }
}
