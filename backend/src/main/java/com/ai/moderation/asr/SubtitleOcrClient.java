package com.ai.moderation.asr;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.AsrProperties;
import com.ai.moderation.config.SubtitleOcrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
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
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 画面腿(腿 B)的 HTTP 客户端:把整段视频以 multipart 上传 ocr-service(:9001),取画面硬字幕文本及其包围盒。
 *
 * <p>关键设计:画面字幕腿在检测管线中<b>可降级</b>——未启用、空结果均返回空 {@link TranscriptionResult},
 * 失败不拖垮主干音频腿(对照不降级的 {@link WhisperAsrClient})。
 *
 * <p>为应对 OCR 服务冷启动(首次加载 Paddle 模型)期间的瞬时连接抖动,{@link #sendMultipartWithRetry}
 * 对瞬时连接失败做退避重试;视频可能很大,故用 {@link SequenceInputStream} 流式拼包上传,避免整文件入内存。
 */
@Component
public class SubtitleOcrClient {
    /** 瞬时连接失败的最大尝试次数(含首次),用于覆盖 OCR 冷启动期的连接抖动。 */
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

    /**
     * 识别视频画面硬字幕,返回带包围盒的字幕结果。
     *
     * <p>降级语义:OCR 未启用或服务返回空时一律返回空结果,使管线照常以音频腿产出继续;
     * 仅在已重试仍失败时抛 {@link ApiException}。
     *
     * @param videoPath 视频文件路径
     * @return 识别结果;未启用或无产出时为空集合,不会为 null
     */
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

    /**
     * 带退避重试的上传:仅对瞬时连接失败(见 {@link #isTransientConnectionFailure})重试,
     * 退避间隔随尝试次数线性增长(500ms × 尝试序号),以容忍 OCR 冷启动期间服务尚未就绪的连接抖动。
     * 非瞬时失败或达到 {@link #MAX_TRANSIENT_IO_ATTEMPTS} 上限即抛出。
     *
     * @param videoPath 视频文件路径
     * @return 识别结果
     * @throws IOException          重试耗尽后的最后一次失败
     * @throws InterruptedException 退避休眠或发送被中断
     */
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

    /**
     * 单次 multipart 上传:前后缀(表单字段 + 文件头 / 收尾边界)拼字节,文件本体经
     * {@link #multipartStream} 以 {@link SequenceInputStream} 流式串接,避免大视频整文件读入内存。
     * 强制 HTTP/1.1 规避协议协商问题。
     *
     * @param videoPath 视频文件路径
     * @return 反序列化后的识别结果
     * @throws IOException          网络/读取失败
     * @throws InterruptedException 发送被中断
     */
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

    /**
     * 凭异常消息粗判是否为瞬时连接失败(可重试)。覆盖 OCR 冷启动期常见的「服务端尚未读到请求头即断开」
     * 「连接被重置/拒绝/关闭」「响应意外截断」等情形;消息为空则视为非瞬时、不重试。
     *
     * @param ex 捕获的 IO 异常
     * @return 判定为瞬时连接失败返回 true
     */
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

    /** 拼接 OCR 端点 URI:baseUrl 为空时回退复用 ASR 服务地址,并容忍末尾/起始斜杠的有无。 */
    private URI ocrUri() {
        String rawBase = (ocrProperties.baseUrl() != null && !ocrProperties.baseUrl().isBlank())
                ? ocrProperties.baseUrl()
                : asrProperties.baseUrl();
        String baseUrl = rawBase.endsWith("/") ? rawBase.substring(0, rawBase.length() - 1) : rawBase;
        String path = ocrProperties.ocrPath().startsWith("/")
                ? ocrProperties.ocrPath()
                : "/" + ocrProperties.ocrPath();
        return URI.create(baseUrl + path);
    }

    /**
     * 把「前缀字节 + 视频文件输入流 + 后缀字节」串成单一输入流,供 BodyPublisher 流式读取,
     * 避免将整段视频读入内存。文件流打开失败时包装为 {@link UncheckedIOException}(供应者签名不允许抛检查异常)。
     */
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

    /**
     * 构造 multipart 包体的前缀部分:OCR 调参表单字段(抽帧间隔、底部裁剪比例、最低置信度)及文件字段头,
     * 文件名转义双引号。真正的文件本体在 {@link #multipartStream} 中接续。
     */
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

    /** 向包体追加一个普通 multipart 文本字段(form-data)。 */
    private void appendFormField(StringBuilder builder, String boundary, String name, String value) {
        builder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    /** 固定 3 位小数、Locale 无关地格式化 double,确保上送 OCR 服务的参数格式稳定(避免区域小数点差异)。 */
    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
