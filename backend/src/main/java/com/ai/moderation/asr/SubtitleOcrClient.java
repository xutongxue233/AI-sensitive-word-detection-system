package com.ai.moderation.asr;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.AsrProperties;
import com.ai.moderation.config.SubtitleOcrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class SubtitleOcrClient {
    private static final int MAX_TRANSIENT_IO_ATTEMPTS = 3;

    private final AsrProperties asrProperties;
    private final SubtitleOcrProperties ocrProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public SubtitleOcrClient(
            AsrProperties asrProperties,
            SubtitleOcrProperties ocrProperties,
            ObjectMapper objectMapper
    ) {
        this.asrProperties = asrProperties;
        this.ocrProperties = ocrProperties;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return ocrProperties.enabled();
    }

    public TranscriptionResult recognize(Path videoPath) {
        if (!ocrProperties.enabled()) {
            return new TranscriptionResult(List.of());
        }
        try {
            TranscriptionResult result = sendMultipartWithRetry(videoPath);
            if (result == null || result.segments() == null) {
                return new TranscriptionResult(List.of());
            }
            return result;
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "字幕 OCR 调用被中断");
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "字幕 OCR 调用失败: " + ex.getMessage());
        }
    }

    private TranscriptionResult sendMultipartWithRetry(Path videoPath) throws IOException, InterruptedException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_IO_ATTEMPTS; attempt++) {
            try {
                return sendMultipart(videoPath);
            } catch (IOException ex) {
                lastException = ex;
                if (attempt == MAX_TRANSIENT_IO_ATTEMPTS || !isTransientConnectionFailure(ex)) {
                    throw ex;
                }
                Thread.sleep(500L * attempt);
            }
        }
        throw lastException;
    }

    private TranscriptionResult sendMultipart(Path videoPath) throws IOException, InterruptedException {
        String boundary = "----subtitle-ocr-" + UUID.randomUUID();
        byte[] prefix = multipartPrefix(boundary, videoPath).getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(ocrUri())
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> multipartStream(videoPath, prefix, suffix)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "字幕 OCR 服务调用失败 HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), TranscriptionResult.class);
    }

    private boolean isTransientConnectionFailure(IOException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("header parser received no bytes")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused")
                || normalized.contains("closed")
                || normalized.contains("unexpected end");
    }

    private URI ocrUri() {
        String baseUrl = asrProperties.baseUrl().endsWith("/")
                ? asrProperties.baseUrl().substring(0, asrProperties.baseUrl().length() - 1)
                : asrProperties.baseUrl();
        String path = ocrProperties.ocrPath().startsWith("/")
                ? ocrProperties.ocrPath()
                : "/" + ocrProperties.ocrPath();
        return URI.create(baseUrl + path);
    }

    private InputStream multipartStream(Path videoPath, byte[] prefix, byte[] suffix) {
        try {
            List<InputStream> streams = List.of(
                    new ByteArrayInputStream(prefix),
                    java.nio.file.Files.newInputStream(videoPath),
                    new ByteArrayInputStream(suffix)
            );
            return new SequenceInputStream(Collections.enumeration(streams));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String multipartPrefix(String boundary, Path videoPath) {
        StringBuilder builder = new StringBuilder();
        appendFormField(builder, boundary, "interval_seconds", format(ocrProperties.intervalSeconds()));
        appendFormField(builder, boundary, "crop_bottom_ratio", format(ocrProperties.cropBottomRatio()));
        appendFormField(builder, boundary, "min_confidence", format(ocrProperties.minConfidence()));
        String filename = videoPath.getFileName().toString().replace("\"", "_");
        builder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        return builder.toString();
    }

    private void appendFormField(StringBuilder builder, String boundary, String name, String value) {
        builder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
