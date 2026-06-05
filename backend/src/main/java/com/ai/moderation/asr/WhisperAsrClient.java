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

/**
 * 音频腿(腿 A)的 HTTP 客户端:把 16k 单声道 wav 以 multipart 上传 asr-service(:9000),
 * 请求 Whisper 词级时间戳转写。
 *
 * <p>关键设计:音频腿是检测管线的主干,<b>失败即任务失败,绝不降级</b>——HTTP 非 2xx、空结果、IO/中断
 * 一律抛 {@link ApiException}。这与可降级的画面字幕腿 {@link SubtitleOcrClient}(失败返回空、不拖垮音频腿)
 * 形成对照。
 */
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

    /**
     * 构造并发送 multipart 请求到 asr-service。音频文件较小,故 {@link #addFileField} 直接全量读入内存拼包,
     * 无需流式上传。强制 HTTP/1.1 避免与服务端 h2 协商问题;附带 {@code word_timestamps=true} 索取词级时间戳。
     *
     * @param audioPath 音频文件路径
     * @return 反序列化后的转写结果
     * @throws IOException          网络/读取失败
     * @throws InterruptedException 发送被中断
     */
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

    /** 追加一个普通 multipart 文本字段(form-data)。 */
    private void addFormField(List<byte[]> body, String boundary, String name, String value) {
        body.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 追加一个文件 multipart 字段:文件名转义双引号后写头,随后将文件内容<b>全量读入内存</b>拼入包体。
     * 仅适用于较小音频;大文件应改用流式上传(参见 {@link SubtitleOcrClient} 的 SequenceInputStream 方案)。
     */
    private void addFileField(List<byte[]> body, String boundary, String name, Path file) throws IOException {
        String filename = file.getFileName().toString().replace("\"", "_");
        body.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.add(Files.readAllBytes(file));
        body.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
