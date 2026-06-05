package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 单条命中的 AI 上下文复核结果实体,对应表 {@code ai_reviews}。
 * <p>由 AI_REVIEWING 阶段的逐条复核(回退路径)写入,记录模型对某条 {@link TermHit} 是否构成违规的判定;
 * 与 {@link TermHit#getReviewStatus()}/{@link TermHit#getAiConfidence()} 配合,留存判定依据供审计回溯。
 */
@Getter
@Setter
@TableName("ai_reviews")
public class AiReview implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外键,关联被复核的命中记录 {@link TermHit#getId()}。 */
    private Long hitId;

    /** 模型判定是否构成违规;与 {@code confidence} 及置信度阈值共同决定命中最终是否判违规。 */
    private boolean violation;

    /** 模型给出的置信度,与设置中的阈值比较以决定是否置 VIOLATION。 */
    private Double confidence;

    /** 模型归类的违规类别。 */
    private String category;

    /** 模型给出的判定理由,用于在工作台展示与人工审计。 */
    private String reason;

    /** 模型返回的原始报文,留存供审计与排查解析问题。 */
    private String rawResponse;

    private Instant createdAt = Instant.now();
}
