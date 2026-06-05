package com.ai.moderation.domain;

/**
 * 命中记录 {@link TermHit} 的复核状态。
 *
 * <p>由 AI 复核(置信度把关)与人工裁决共同决定:仅判违规且置信度达阈值的命中才进时间轴并生成剪辑。</p>
 */
public enum ReviewStatus {
    /** 待复核:已召回但未经 AI 判定。 */
    PENDING,
    /** 违规:AI 判违规且置信度 ≥ 阈值,进时间轴并生成剪辑建议。 */
    VIOLATION,
    /** 安全:未达阈值或 AI 判安全,保留记录但不自动剪。 */
    SAFE,
    /** 人工确认:经人工核实确属违规。 */
    CONFIRMED,
    /** 人工驳回:经人工核实判为误召。 */
    IGNORED
}

