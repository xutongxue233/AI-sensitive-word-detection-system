package com.ai.moderation.controller;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpHeaders;
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

@RestController
@RequestMapping("/api/v1")
public class VideoContentController {
    private static final long RANGE_CHUNK_SIZE = 1024 * 1024;

    private final VideoFileRepository videoRepository;
    private final ClipSuggestionRepository suggestionRepository;

    public VideoContentController(VideoFileRepository videoRepository, ClipSuggestionRepository suggestionRepository) {
        this.videoRepository = videoRepository;
        this.suggestionRepository = suggestionRepository;
    }

    @GetMapping("/videos/{id}/content")
    public ResponseEntity<?> videoContent(@PathVariable Long id, @RequestHeader HttpHeaders headers) {
        String path = videoRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "视频不存在"))
                .getStoragePath();
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

    private byte[] readRange(Path path, long start, long end) {
        int length = Math.toIntExact(end - start + 1);
        try (InputStream input = Files.newInputStream(path)) {
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
