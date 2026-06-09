package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.AsrProperties;
import com.ai.moderation.config.FfmpegProperties;
import com.ai.moderation.config.SubtitleOcrProperties;
import com.ai.moderation.dto.HealthItemResponse;
import com.ai.moderation.dto.SystemHealthResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 系统健康检查:面向前端侧边栏展示关键依赖的真实状态。
 *
 * <p>检查保持轻量:数据库只验证连接,FFmpeg 只跑 {@code -version},ASR/OCR 只访问根路径,
 * AI 只检查运行时配置完整性,不会实际消耗模型调用额度。
 */
@Service
public class SystemHealthService {
    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String DOWN = "DOWN";
    private static final Duration HTTP_TIMEOUT = Duration.ofMillis(1200);
    private static final long PROCESS_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final FfmpegProperties ffmpegProperties;
    private final AsrProperties asrProperties;
    private final SubtitleOcrProperties ocrProperties;
    private final SettingsService settingsService;
    private final HttpClient httpClient;

    public SystemHealthService(
            DataSource dataSource,
            FfmpegProperties ffmpegProperties,
            AsrProperties asrProperties,
            SubtitleOcrProperties ocrProperties,
            SettingsService settingsService
    ) {
        this.dataSource = dataSource;
        this.ffmpegProperties = ffmpegProperties;
        this.asrProperties = asrProperties;
        this.ocrProperties = ocrProperties;
        this.settingsService = settingsService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    public SystemHealthResponse check() {
        List<HealthItemResponse> items = new ArrayList<>();
        items.add(checkDatabase());
        items.add(checkFfmpeg());
        items.add(checkHttpDependency("asr", "ASR", asrProperties.enabled(), asrProperties.baseUrl()));
        items.add(checkHttpDependency("ocr", "OCR", ocrProperties.enabled(), ocrProperties.baseUrl()));
        items.add(checkAiConfig());
        return new SystemHealthResponse(overallStatus(items), Instant.now(), items);
    }

    private HealthItemResponse checkDatabase() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(1);
            return item("database", "数据库", valid ? OK : DOWN, valid ? "连接正常" : "连接无效", start);
        } catch (Exception ex) {
            return item("database", "数据库", DOWN, "连接失败: " + concise(ex), start);
        }
    }

    private HealthItemResponse checkFfmpeg() {
        long start = System.nanoTime();
        String ffmpeg = ffmpegProperties.ffmpegPath();
        String ffprobe = ffmpegProperties.ffprobePath();
        if (!StringUtils.hasText(ffmpeg) || !StringUtils.hasText(ffprobe)) {
            return item("ffmpeg", "FFmpeg", DOWN, "未配置 ffmpeg 或 ffprobe 路径", start);
        }
        ProbeResult ffmpegResult = probeProcess(ffmpeg);
        if (!ffmpegResult.ok()) {
            return item("ffmpeg", "FFmpeg", DOWN, "ffmpeg 不可用: " + ffmpegResult.message(), start);
        }
        ProbeResult ffprobeResult = probeProcess(ffprobe);
        if (!ffprobeResult.ok()) {
            return item("ffmpeg", "FFmpeg", DOWN, "ffprobe 不可用: " + ffprobeResult.message(), start);
        }
        return item("ffmpeg", "FFmpeg", OK, "ffmpeg / ffprobe 可用", start);
    }

    private HealthItemResponse checkHttpDependency(String key, String label, boolean enabled, String baseUrl) {
        long start = System.nanoTime();
        if (!enabled) {
            return item(key, label, WARN, "未启用", start);
        }
        if (!StringUtils.hasText(baseUrl)) {
            return item(key, label, DOWN, "未配置服务地址", start);
        }
        try {
            URI uri = rootUri(baseUrl);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code >= 200 && code < 400) {
                return item(key, label, OK, "服务可达", start);
            }
            if (code == 404) {
                return item(key, label, WARN, "服务可达,但根路径未提供健康页", start);
            }
            return item(key, label, DOWN, "HTTP " + code, start);
        } catch (Exception ex) {
            return item(key, label, DOWN, "不可达: " + concise(ex), start);
        }
    }

    private HealthItemResponse checkAiConfig() {
        long start = System.nanoTime();
        try {
            AiProperties ai = settingsService.currentAi();
            if (!ai.enabled()) {
                return item("ai", "AI 复核", WARN, "未启用", start);
            }
            if (!StringUtils.hasText(ai.baseUrl())) {
                return item("ai", "AI 复核", DOWN, "未配置接口地址", start);
            }
            if (!StringUtils.hasText(ai.model())) {
                return item("ai", "AI 复核", DOWN, "未配置模型", start);
            }
            return item("ai", "AI 复核", OK, "配置完整: " + ai.model(), start);
        } catch (Exception ex) {
            return item("ai", "AI 复核", DOWN, "设置读取失败: " + concise(ex), start);
        }
    }

    private ProbeResult probeProcess(String executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new ProbeResult(false, "执行超时");
            }
            int exitCode = process.exitValue();
            return exitCode == 0
                    ? new ProbeResult(true, "可执行")
                    : new ProbeResult(false, "退出码 " + exitCode);
        } catch (IOException ex) {
            return new ProbeResult(false, concise(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "检查被中断");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private URI rootUri(String baseUrl) {
        String normalized = baseUrl.trim();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return URI.create(normalized);
    }

    private HealthItemResponse item(String key, String label, String status, String message, long startNanos) {
        return new HealthItemResponse(key, label, status, message, elapsedMs(startNanos));
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String overallStatus(List<HealthItemResponse> items) {
        if (items.stream().anyMatch((item) -> DOWN.equals(item.status()))) {
            return DOWN;
        }
        if (items.stream().anyMatch((item) -> WARN.equals(item.status()))) {
            return WARN;
        }
        return OK;
    }

    private String concise(Exception ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }

    private record ProbeResult(boolean ok, String message) {
    }
}
