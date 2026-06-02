package com.ai.moderation.asr;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.AsrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class WhisperAsrClient {
    private final AsrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public WhisperAsrClient(AsrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public TranscriptionResult transcribe(Path audioPath) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 未启用，请上传字幕文件或开启 app.asr.enabled");
        }
        try {
            TranscriptionResult result = sendMultipart(audioPath);
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

    private TranscriptionResult sendMultipart(Path audioPath) throws IOException, InterruptedException {
        String boundary = "----asr-" + UUID.randomUUID();
        List<byte[]> body = new ArrayList<>();
        addFormField(body, boundary, "word_timestamps", "true");
        addFileField(body, boundary, "file", audioPath);
        body.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(asrUri())
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASR 服务调用失败 HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), TranscriptionResult.class);
    }

    private URI asrUri() {
        String baseUrl = properties.baseUrl().endsWith("/")
                ? properties.baseUrl().substring(0, properties.baseUrl().length() - 1)
                : properties.baseUrl();
        String path = properties.transcribePath().startsWith("/")
                ? properties.transcribePath()
                : "/" + properties.transcribePath();
        return URI.create(baseUrl + path);
    }

    private void addFormField(List<byte[]> body, String boundary, String name, String value) {
        body.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void addFileField(List<byte[]> body, String boundary, String name, Path file) throws IOException {
        String filename = file.getFileName().toString().replace("\"", "_");
        body.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.add(Files.readAllBytes(file));
        body.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
