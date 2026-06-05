package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FFmpeg/ffprobe 可执行文件路径与 delogo 去字幕渲染参数,绑定 {@code app.ffmpeg.*}。
 *
 * <p>二进制不入库(由本地提供或环境变量 {@code FFMPEG_PATH}/{@code FFPROBE_PATH} 覆盖)。
 * GPU 去字幕(VSR)已移除,画面字幕统一用 CPU 的 ffmpeg delogo 邻域插值 + 高斯柔化 + 边缘羽化擦除。
 *
 * @param ffmpegPath         ffmpeg 可执行文件路径,可被环境变量 {@code FFMPEG_PATH} 覆盖
 * @param ffprobePath        ffprobe 可执行文件路径,可被环境变量 {@code FFPROBE_PATH} 覆盖
 * @param subtitleBlurSigma  字幕擦除后高斯柔化的 sigma,默认 6.0,越大越糊
 * @param subtitleFeatherMax 字幕框边缘羽化的最大像素宽度,默认 40,用于过渡擦除区与原画
 */
@ConfigurationProperties(prefix = "app.ffmpeg")
public record FfmpegProperties(
        String ffmpegPath,
        String ffprobePath,
        Double subtitleBlurSigma,
        Integer subtitleFeatherMax
) {
    /** 紧凑构造器:旧配置未提供这两项渲染参数时用默认值兜底,保证启动不失败。 */
    public FfmpegProperties {
        if (subtitleBlurSigma == null) {
            subtitleBlurSigma = 6.0;
        }
        if (subtitleFeatherMax == null) {
            subtitleFeatherMax = 40;
        }
    }
}

