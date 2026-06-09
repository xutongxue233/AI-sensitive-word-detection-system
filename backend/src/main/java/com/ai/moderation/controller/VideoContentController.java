package com.ai.moderation.controller;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 视频二进制流式播放的 REST 入口,前缀 {@code /api/v1}。
 *
 * <p>负责把原始上传视频({@code /videos/{id}/content})与导出成片
 * ({@code /videos/{id}/export-content}、{@code /clip-suggestions/{id}/export-content})以字节流形式返回给浏览器播放器,
 * 支持 HTTP Range 分片请求(拖动进度条按需取片段)。因返回的是二进制流而非 JSON,
 * 与 {@link VideoController} 等 JSON 控制器刻意分开。
 */
@RestController
@RequestMapping("/api/v1")
public class VideoContentController {
    /** 单次 Range 请求返回的最大字节数(1MB),用于限制单次响应占用的内存,避免一次性加载大文件。 */
    private static final long RANGE_CHUNK_SIZE = 1024 * 1024;

    private final VideoFileRepository videoRepository;
    private final DetectionJobRepository jobRepository;
    private final ClipSuggestionRepository suggestionRepository;

    public VideoContentController(
            VideoFileRepository videoRepository,
            DetectionJobRepository jobRepository,
            ClipSuggestionRepository suggestionRepository
    ) {
        this.videoRepository = videoRepository;
        this.jobRepository = jobRepository;
        this.suggestionRepository = suggestionRepository;
    }

    @GetMapping("/videos/{id}/content")
    public ResponseEntity<?> videoContent(@PathVariable Long id, @RequestHeader HttpHeaders headers) {
        String path = videoRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"))
                .getStoragePath();
        return fileResponse(Path.of(path), "video/mp4", headers);
    }

    @GetMapping("/videos/{id}/export-content")
    public ResponseEntity<?> videoExportContent(@PathVariable Long id, @RequestHeader HttpHeaders headers) {
        videoRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"));
        String path = jobRepository.findByVideoIdOrderByCreatedAtDesc(id)
                .stream()
                .flatMap(job -> suggestionRepository
                        .findByJobIdAndStatusOrderByStartTimeAsc(job.getId(), ClipStatus.EXPORTED)
                        .stream())
                .filter(item -> item.getExportPath() != null && !item.getExportPath().isBlank())
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "导出文件不存在"))
                .getExportPath();
        return fileResponse(Path.of(path), "video/mp4", headers);
    }

    @GetMapping("/clip-suggestions/{id}/export-content")
    public ResponseEntity<?> exportContent(@PathVariable Long id, @RequestHeader HttpHeaders headers) {
        String path = suggestionRepository.findById(id)
                .filter(item -> item.getStatus() == ClipStatus.EXPORTED && item.getExportPath() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "导出文件不存在"))
                .getExportPath();
        return fileResponse(Path.of(path), "video/mp4", headers);
    }

    /**
     * 根据请求是否携带 Range 头返回完整文件或单个分片。
     *
     * <p>无 Range 时返回 200 全量响应,并声明 {@code Accept-Ranges: bytes} 告知客户端支持分片;
     * 有 Range 时返回 206 部分内容,把请求区间裁剪到不超过 {@link #RANGE_CHUNK_SIZE} 的单片,
     * 配合 {@code Content-Range} 头让播放器自行续取后续片段。
     *
     * @param path           待返回的文件路径
     * @param contentType    响应的 MIME 类型(如 {@code video/mp4})
     * @param requestHeaders 客户端请求头,用于读取 Range
     * @return 200 全量或 206 分片的响应
     */
    private ResponseEntity<?> fileResponse(Path path, String contentType, HttpHeaders requestHeaders) {
        if (!Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        FileSystemResource resource = new FileSystemResource(path);
        MediaType mediaType = MediaType.parseMediaType(contentType);
        long contentLength = fileSize(path);

        if (requestHeaders.getRange().isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentLength(contentLength)
                    .contentType(mediaType)
                    .body(resource);
        }

        HttpRange range = requestHeaders.getRange().getFirst();
        long start = range.getRangeStart(contentLength);
        long requestedEnd = range.getRangeEnd(contentLength);
        long end = Math.min(requestedEnd, start + RANGE_CHUNK_SIZE - 1);
        byte[] chunk = readRange(path, start, end);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength)
                .contentLength(chunk.length)
                .contentType(mediaType)
                .body(chunk);
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取视频文件失败");
        }
    }

    /**
     * 从文件的 {@code start} 偏移读取 {@code [start, end]} 闭区间内的字节。
     *
     * @param path  文件路径
     * @param start 起始偏移(含)
     * @param end   结束偏移(含)
     * @return 该区间的字节数组
     */
    private byte[] readRange(Path path, long start, long end) {
        int length = Math.toIntExact(end - start + 1);
        try (InputStream input = Files.newInputStream(path)) {
            // InputStream.skip 不保证一次跳到位(可能少跳),故循环累加直至到达起始偏移。
            long skipped = input.skip(start);
            while (skipped < start) {
                long next = input.skip(start - skipped);
                if (next <= 0) {
                    throw new IOException("Unable to skip to requested range");
                }
                skipped += next;
            }
            return input.readNBytes(length);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取视频文件失败");
        }
    }
}
