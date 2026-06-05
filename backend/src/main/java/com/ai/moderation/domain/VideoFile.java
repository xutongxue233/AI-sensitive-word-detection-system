package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 上传视频元数据实体,对应表 {@code videos}(类名 VideoFile 与表名不一致)。
 * <p>记录上传视频的存储信息与基本属性,是 {@link DetectionJob} 的检测对象;
 * {@code subtitlePath} 有值时管线跳过画面 OCR 腿改用外部字幕,{@code width}/{@code height} 供去字幕坐标换算。
 */
@Getter
@Setter
@TableName("videos")
public class VideoFile implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传时的原始文件名(展示用)。 */
    private String originalFilename;

    /** 落盘存储的文件名,区别于上传原名以避免冲突。 */
    private String storedFilename;

    private String storagePath;

    /** 外部字幕文件路径;有值则管线跳过画面 OCR 腿,改用该字幕。 */
    private String subtitlePath;

    private Double durationSeconds;

    private Long sizeBytes;

    private String contentType;

    /** 视频像素宽度,供去字幕时将归一化字幕框坐标换算回像素。 */
    private Integer width;

    /** 视频像素高度,供去字幕时将归一化字幕框坐标换算回像素。 */
    private Integer height;

    /** 视频处理状态,取值见 {@link VideoStatus}。 */
    private VideoStatus status = VideoStatus.UPLOADED;

    private Instant createdAt = Instant.now();
}
