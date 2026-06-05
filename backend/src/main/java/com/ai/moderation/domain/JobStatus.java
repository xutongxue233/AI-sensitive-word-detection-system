package com.ai.moderation.domain;

/**
 * 检测任务的阶段状态机,由检测管线 {@link com.ai.moderation.service.DetectionPipelineService} 推进。
 *
 * <p>各阶段在执行时回写 {@code JobStatus} 与进度百分比,前端轮询 {@code GET /jobs/{id}} 展示。
 * 除可降级的画面 OCR 腿外,任一阶段失败即落到 {@link #FAILED}。</p>
 */
public enum JobStatus {
    /** 已入队,等待异步管线调度。 */
    QUEUED,
    /** 抽取音频阶段(进度 10):FFmpeg 抽 16k mono wav。 */
    EXTRACTING_AUDIO,
    /** 转写落库阶段(进度 35):Whisper/OCR 结果合并后写入 segments/words。 */
    TRANSCRIBING,
    /** 规则召回阶段(进度 55):规则匹配召回敏感词候选。 */
    MATCHING_TERMS,
    /** AI 复核阶段(进度 75):整篇提取 / 逐条复核判定候选。 */
    AI_REVIEWING,
    /** 生成剪辑建议阶段(进度 90):据命中产出剪辑 / 去字幕建议。 */
    SUGGESTING_CLIPS,
    /** 已完成(进度 100)。 */
    COMPLETED,
    /** 失败:任一不可降级阶段抛错。 */
    FAILED
}

