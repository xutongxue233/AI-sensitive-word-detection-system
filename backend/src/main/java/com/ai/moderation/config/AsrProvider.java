package com.ai.moderation.config;

/**
 * 语音转写引擎选择。运行时由系统设置(齿轮面板)决定,对新建检测任务即时生效。
 * 两种取值都经由 asr-service(:9000)进程完成,后端只透传参数。
 */
public enum AsrProvider {
    /** 本地 faster-whisper(纯 CPU,词级时间戳) */
    LOCAL,
    /** 在线 ASR(OpenAI Chat Completions 兼容,如小米 MiMo;由 asr-service 做 VAD 切片与伪词级时间戳) */
    ONLINE
}
