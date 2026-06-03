package com.ai.moderation.asr;

import com.ai.moderation.config.SubtitleRemovalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 调用独立的 vsr-service(Video-Subtitle-Remover)对单个视频片段做字幕区域 inpainting 去除。
 * 只去除传入的像素区域(由命中敏感词的字幕框换算而来),不让 VSR 自动擦全部。
 * 任何失败都返回 false,由调用方回退到 delogo,保证导出不被 VSR 阻断。
 */
@Component
public class SubtitleRemovalClient {
    private static final Logger log = LoggerFactory.getLogger(SubtitleRemovalClient.class);

    private final SubtitleRemovalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public SubtitleRemovalClient(SubtitleRemovalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return properties.useVsr();
    }

    /**
     * @param clip   视频片段(后端按违规时间段切出)
     * @param areas  每个元素为 [ymin, ymax, xmin, xmax] 整数像素区域,支持多个
     * @param output 处理后片段写入路径
     * @return 成功 true;失败 false(调用方回退 delogo)
     */
    public boolean removeSubtitle(Path clip, List<int[]> areas, Path output) {
        if (areas == null || areas.isEmpty()) {
            return false;
        }
        try {
            String areasJson = objectMapper.writeValueAsString(areas);
            String boundary = "----vsr-" + UUID.randomUUID();
            byte[] prefix = multipartPrefix(boundary, clip, areasJson).getBytes(StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(removeUri())
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(Math.max(1, properties.timeoutSeconds())))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> multipartStream(clip, prefix, suffix)))
                    .build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(output));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("VSR 去字幕失败 HTTP {}: 片段 {}", response.statusCode(), clip.getFileName());
                return false;
            }
            return Files.exists(output) && Files.size(output) > 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("VSR 去字幕被中断: {}", ex.getMessage());
            return false;
        } catch (Exception ex) {
            log.warn("VSR 去字幕调用失败,将回退 delogo: {}", ex.getMessage());
            return false;
        }
    }

    private URI removeUri() {
        String baseUrl = properties.baseUrl().endsWith("/")
                ? properties.baseUrl().substring(0, properties.baseUrl().length() - 1)
                : properties.baseUrl();
        String path = properties.removePath().startsWith("/") ? properties.removePath() : "/" + properties.removePath();
        return URI.create(baseUrl + path);
    }

    private String multipartPrefix(String boundary, Path clip, String areasJson) {
        StringBuilder b = new StringBuilder();
        appendField(b, boundary, "areas", areasJson);
        appendField(b, boundary, "inpaint_mode",
                properties.inpaintMode() == null ? "sttn_auto" : properties.inpaintMode());
        String filename = clip.getFileName().toString().replace("\"", "_");
        b.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        return b.toString();
    }

    private void appendField(StringBuilder b, String boundary, String name, String value) {
        b.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private InputStream multipartStream(Path clip, byte[] prefix, byte[] suffix) {
        try {
            List<InputStream> streams = List.of(
                    new ByteArrayInputStream(prefix),
                    Files.newInputStream(clip),
                    new ByteArrayInputStream(suffix)
            );
            return new SequenceInputStream(Collections.enumeration(streams));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
