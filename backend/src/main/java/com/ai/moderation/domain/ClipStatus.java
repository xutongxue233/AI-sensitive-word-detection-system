package com.ai.moderation.domain;

/**
 * 剪辑 / 去字幕建议({@link ClipSuggestion})的生命周期状态。
 *
 * <p>从生成建议到产出导出成品依次流转,人工可在前端确认或驳回单条建议。</p>
 */
public enum ClipStatus {
    /** 待确认:系统生成的建议,尚未经人工裁决。 */
    PENDING,
    /** 已确认:人工确认采纳,等待导出处理。 */
    CONFIRMED,
    /** 已驳回:人工判定无需处理,导出时跳过。 */
    IGNORED,
    /** 已导出:对应剪辑 / 去字幕成品已生成。 */
    EXPORTED
}

