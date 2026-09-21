package com.ai.moderation.asr;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.AsrProperties;
import com.ai.moderation.service.SettingsService;
import com.ai.moderation.service.support.AsrOnlineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 音频腿(腿 A)的 HTTP 客户端:把 16k 单声道 wav 以 multipart 上传 asr-service(:9000),
 * 请求 Whisper 词级时间戳转写。
 *
 * <p>关键设计:音频腿是检测管线的主干,<b>失败即任务失败,绝不降级</b>——HTTP 非 2xx、空结果、IO/中断
 * 一律抛 {@link ApiException}。这与可降级的画面字幕腿 {@link SubtitleOcrClient}(失败返回空、不拖垮音频腿)
 * 形成对照。
 *
 * <p>运行时设置选择 ONLINE 引擎时,仍走同一 asr-service 进程,仅把在线端点/密钥/模型以 form
 * 字段透传过去,由其调用 OpenAI Chat Completions 兼容的在线识别(如小米 MiMo)并补伪词级时间戳;
 * 响应结构与本地模式一致,本类反序列化逻辑不变。
 */
@Component
public class WhisperAsrClient {
    private final AsrProperties properties;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public WhisperAsrClient(AsrProperties properties, SettingsService settingsService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 转写音频文件,返回带词级时间戳的字幕结果。
     *
     * <p>失败语义为「不降级」:未启用、空结果、IO/中断均转为 {@link ApiException} 上抛,使整个检测任务失败。
     *
     * @param audioPath 已抽取的 16k 单声道 wav 路径
     * @return 非空的转写结果
     * @throws ApiException ASR 未启用、服务异常、结果为空或调用被中断
     */
    public TranscriptionResult transcribe(Path audioPath) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 未启用，请上传字幕文件或开启 app.asr.enabled");
        }
        AsrOnlineSettings asrSettings = settingsService.currentAsr();
        if (asrSettings.online() && (asrSettings.apiKey() == null || asrSettings.apiKey().isBlank())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "在线 ASR 未配置 API Key，请在系统设置中填写或切回本地引擎");
        }
        try {
            TranscriptionResult result = sendMultipart(audioPath, asrSettings);
            if (result == null || result.segments() == null || result.segments().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 服务未返回有效字幕结果");
            }
            return result;
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 调用被中断");
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 调用失败: " + ex.getMessage());
        }
    }

    /**
     * 构造并发送 multipart 请求到 asr-service。音频本体通过 {@code InputStream} 分段读取，避免把整段音频
     * 再复制到 Java 堆。强制 HTTP/1.1 避免与服务端 h2 协商问题;附带 {@code word_timestamps=true} 索取词级时间戳。
     * 在线引擎时追加 provider/online_* form 字段,asr-service 据此改调在线识别接口。
     *
     * @param audioPath   音频文件路径
     * @param asrSettings 运行时 ASR 引擎设置
     * @return 反序列化后的转写结果
     * @throws IOException          网络/读取失败
     * @throws InterruptedException 发送被中断
     */
    private TranscriptionResult sendMultipart(Path audioPath, AsrOnlineSettings asrSettings) throws IOException, InterruptedException {
        String boundary = "----asr-" + UUID.randomUUID();
        byte[] prefix = multipartPrefix(boundary, audioPath, asrSettings);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(asrUri())
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> multipartStream(audioPath, prefix, suffix)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 服务调用失败 HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), TranscriptionResult.class);
    }

    /** 拼接转写端点 URI,容忍 baseUrl 末尾斜杠与 transcribePath 起始斜杠的有无。 */
    private URI asrUri() {
        String baseUrl = properties.baseUrl().endsWith("/")
                ? properties.baseUrl().substring(0, properties.baseUrl().length() - 1)
                : properties.baseUrl();
        String path = properties.transcribePath().startsWith("/")
                ? properties.transcribePath()
                : "/" + properties.transcribePath();
        return URI.create(baseUrl + path);
    }

    /** 构造 multipart 前缀(普通字段与文件头);音频文件本体由流式 BodyPublisher 读取。 */
    private byte[] multipartPrefix(String boundary, Path audioPath, AsrOnlineSettings asrSettings) {
        StringBuilder builder = new StringBuilder();
        appendFormField(builder, boundary, "word_timestamps", "true");
        if (asrSettings.online()) {
            appendFormField(builder, boundary, "provider", "online");
            appendFormField(builder, boundary, "online_base_url", asrSettings.baseUrl());
            appendFormField(builder, boundary, "online_api_key", asrSettings.apiKey());
            appendFormField(builder, boundary, "online_model", asrSettings.model());
        }
        String filename = audioPath.getFileName().toString().replace("\"", "_");
        builder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendFormField(StringBuilder builder, String boundary, String name, String value) {
        builder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private InputStream multipartStream(Path audioPath, byte[] prefix, byte[] suffix) {
        try {
            return new SequenceInputStream(Collections.enumeration(List.of(
                    new ByteArrayInputStream(prefix),
                    Files.newInputStream(audioPath),
                    new ByteArrayInputStream(suffix)
            )));
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
