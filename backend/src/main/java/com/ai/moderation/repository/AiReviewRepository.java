package com.ai.moderation.repository;

import com.ai.moderation.domain.AiReview;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Optional;

public interface AiReviewRepository extends BaseCrudMapper<AiReview> {
    default Optional<AiReview> findByHitId(Long hitId) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(AiReview.class)
                .eq(AiReview::getHitId, hitId)
                .last("LIMIT 1")));
    }
}
