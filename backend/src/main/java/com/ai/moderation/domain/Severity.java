package com.ai.moderation.domain;

/**
 * 敏感词 / 命中的严重级别,由低到高排列,用于风险分级与展示。
 */
public enum Severity {
    /** 低危。 */
    LOW,
    /** 中危。 */
    MEDIUM,
    /** 高危。 */
    HIGH,
    /** 极高危。 */
    CRITICAL
}

