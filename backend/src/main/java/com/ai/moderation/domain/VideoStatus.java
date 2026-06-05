package com.ai.moderation.domain;

/**
 * 视频维度的生命周期状态,区别于任务维度的 {@link JobStatus}。
 *
 * <p>一个视频可发起多次检测任务;此状态描述视频整体所处阶段,供列表与详情展示。</p>
 */
public enum VideoStatus {
    /** 已上传,等待发起检测。 */
    UPLOADED,
    /** 检测中:存在进行中的检测任务。 */
    DETECTING,
    /** 检测完成:检测任务已产出结果。 */
    DETECTED,
    /** 已导出:剪辑 / 去字幕成品已生成。 */
    EXPORTED,
    /** 检测失败。 */
    FAILED
}

