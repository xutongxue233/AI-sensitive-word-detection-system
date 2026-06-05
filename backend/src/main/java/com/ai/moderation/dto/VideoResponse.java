package com.ai.moderation.dto;

import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;

import java.time.Instant;

/**
 * 上传视频的响应体,由 {@link VideoFile} 实体投影而来。
 *
 * @param id               视频主键
 * @param originalFilename 上传时的原始文件名(供展示)
 * @param storedFilename   落盘后的存储文件名(去重/规整后)
 * @param storagePath      视频在服务端的存储路径
 * @param subtitlePath     外部字幕文件路径;无外部字幕时为 null(此时走画面 OCR)
 * @param durationSeconds  视频时长(秒)
 * @param sizeBytes        文件大小(字节)
 * @param contentType      文件 MIME 类型
 * @param width            视频宽度(像素分辨率)
 * @param height           视频高度(像素分辨率)
 * @param status           视频处理状态,见 {@link VideoStatus}
 * @param createdAt        上传时间
 */
public record VideoResponse(
        Long id,
        String originalFilename,
        String storedFilename,
        String storagePath,
        String subtitlePath,
        Double durationSeconds,
        Long sizeBytes,
        String contentType,
        Integer width,
        Integer height,
        VideoStatus status,
        Instant createdAt
) {
    /**
     * 从视频实体投影出响应体。
     *
     * @param video 视频实体
     * @return 对应的响应体
     */
    public static VideoResponse from(VideoFile video) {
        return new VideoResponse(
                video.getId(),
                video.getOriginalFilename(),
                video.getStoredFilename(),
                video.getStoragePath(),
                video.getSubtitlePath(),
                video.getDurationSeconds(),
                video.getSizeBytes(),
                video.getContentType(),
                video.getWidth(),
                video.getHeight(),
                video.getStatus(),
                video.getCreatedAt()
        );
    }
}

