package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 敏感词命中记录实体,对应表 {@code term_hits},是检测管线的中枢。
 * <p>由规则召回与 AI 提取/复核写入,贯穿"召回 → 复核 → 生成剪辑建议 → 导出"全链路:
 * {@code source} 决定导出策略(音频删时间片段 vs 画面 delogo 去字幕),{@code reviewStatus} 与
 * {@code aiConfidence} 共同决定是否最终判违规并进入时间轴。
 */
@Getter
@Setter
@TableName("term_hits")
public class TermHit implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外键,所属检测任务 {@link DetectionJob#getId()}。 */
    private Long jobId;

    /** 外键,命中的词库条目 {@link ViolationTerm#getId()}。 */
    private Long termId;

    /** 外键,命中所在的转写段 {@link TranscriptSegment#getId()}。 */
    private Long segmentId;

    /** 实际命中的文本片段。 */
    private String matchedText;

    /**
     * 命中在段内归一化文本中的字符区间。该字段仅用于规则召回阶段的去重，
     * 不落库；时间戳可能把同一词元内的多个命中映射到相同区间，不能再仅凭时间判断重复。
     */
    @TableField(exist = false)
    private Integer matchStartOffset;

    @TableField(exist = false)
    private Integer matchEndOffset;

    /** 命中所属违规类别。 */
    private String category;

    /** 命中严重程度,取值见 {@link Severity}。 */
    private Severity severity;

    /** 召回该命中的规则类型,取值见 {@link MatchType}。 */
    private MatchType ruleSource;

    /** 命中来源:决定导出策略——AUDIO 删时间片段、VIDEO_SUBTITLE 用 delogo 去字幕,见 {@link TranscriptSource}。 */
    private TranscriptSource source = TranscriptSource.AUDIO;

    /** 命中在时间轴上的起始时间(秒)。 */
    private double startTime;

    /** 命中在时间轴上的结束时间(秒)。 */
    private double endTime;

    /** 命中所在的上下文文本,供 AI 复核与人工审核参考。 */
    private String contextText;

    /** 复核状态,取值见 {@link ReviewStatus};由 AI 复核或人工确认推进。 */
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    /** AI 复核给出的置信度,与设置中的置信度阈值比较以决定是否判违规。 */
    private Double aiConfidence;

    private Instant createdAt = Instant.now();
}
