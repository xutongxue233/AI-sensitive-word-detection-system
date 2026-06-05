package com.ai.moderation.domain;

/**
 * 规则召回的匹配方式,由 {@link com.ai.moderation.service.RuleMatchingService} 据此召回候选。
 *
 * <p>不同方式决定召回的精确度与误召率;{@link #SEMANTIC} 召回的候选仍需交 AI 判定真伪。</p>
 */
public enum MatchType {
    /** 精确匹配:命中词库原词。 */
    EXACT,
    /** 变体匹配:谐音、拆字、形近等词库原词的变形。 */
    VARIANT,
    /** 正则匹配:按词库配置的正则表达式召回。 */
    REGEX,
    /** 语义召回:当前仅价格上下文,靠正则在价格语境召回候选再交 AI 判真伪。 */
    SEMANTIC
}
