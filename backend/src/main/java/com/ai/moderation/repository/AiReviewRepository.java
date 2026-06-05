package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.AiReview;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Collection;
import java.util.Optional;

/**
 * {@code ai_reviews} 表读写,对应实体 {@link AiReview}。
 *
 * <p>在检测管线中保存 AI 复核结论(是否违规、置信度、判定理由),与 {@link com.ai.moderation.domain.TermHit} 一对一:
 * 每条规则/提取命中至多对应一条复核记录。供 AI 审核阶段写入、查询阶段回显复核详情。</p>
 */
public interface AiReviewRepository extends BaseCrudMapper<AiReview> {

    /**
     * 按命中 ID 取复核记录。
     *
     * <p>限定 {@code LIMIT 1}:命中与复核应为一对一,hitId 唯一,取首条即可。</p>
     *
     * @param hitId 命中记录 ID
     * @return 对应复核记录,无则为空
     */
    default Optional<AiReview> findByHitId(Long hitId) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(AiReview.class)
                .eq(AiReview::getHitId, hitId)
                .last("LIMIT 1")));
    }

    /**
     * 按命中 ID 集合批量删除复核记录,通常用于重跑任务前清理旧复核。
     *
     * @param hitIds 命中记录 ID 集合;为空时直接返回,避免生成无 where 条件的全表删除
     */
    default void deleteByHitIds(Collection<Long> hitIds) {
        if (hitIds == null || hitIds.isEmpty()) {
            return;
        }
        delete(Wrappers.lambdaQuery(AiReview.class).in(AiReview::getHitId, hitIds));
    }
}
