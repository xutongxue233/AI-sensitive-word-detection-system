package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whisper ASR 语音转写服务的接入参数,绑定 {@code app.asr.*}。
 *
 * <p>ASR 是独立的 FastAPI 进程(默认 {@code http://127.0.0.1:9000},torch 后端),对应检测管线的音频腿:
 * FFmpeg 抽取 16k 单声道 wav 后交给 Whisper 做词级时间戳转写。音频腿是核心转写来源,
 * <b>失败即任务失败、不降级</b>(与画面 OCR 腿失败降级为空的策略不同)。
 *
 * @param enabled        是否启用 ASR 接入
 * @param baseUrl        ASR 服务基址
 * @param transcribePath 转写接口相对路径
 */
@ConfigurationProperties(prefix = "app.asr")
public record AsrProperties(boolean enabled, String baseUrl, String transcribePath) {
}

