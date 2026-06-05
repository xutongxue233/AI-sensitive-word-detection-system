package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 敏感词词库条目实体,对应表 {@code violation_terms}。
 * <p>规则匹配阶段据此召回命中候选({@link TermHit});整篇 AI 提取时全量词库一并交给模型。
 */
@Getter
@Setter
@TableName("violation_terms")
public class ViolationTerm implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感词文本;当 {@code matchType=REGEX} 时本字段为正则表达式。 */
    private String term;

    private String category;

    /** 严重程度,取值见 {@link Severity}。 */
    private Severity severity = Severity.MEDIUM;

    /** 匹配方式,取值见 {@link MatchType}(EXACT/VARIANT/REGEX/SEMANTIC)。 */
    private MatchType matchType = MatchType.EXACT;

    /** 停用开关,false 则规则匹配时跳过本条。 */
    private boolean enabled = true;

    /** 变体词,供 {@code matchType=VARIANT} 使用,以分隔符存储多个变体。 */
    private String variants;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
