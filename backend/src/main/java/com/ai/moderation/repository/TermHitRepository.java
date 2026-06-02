package com.ai.moderation.repository;

import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface TermHitRepository extends BaseCrudMapper<TermHit> {
    default List<TermHit> findByJobIdOrderByStartTimeAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(TermHit.class)
                .eq(TermHit::getJobId, jobId)
                .orderByAsc(TermHit::getStartTime));
    }

    default List<TermHit> findByJobIdAndReviewStatusOrderByStartTimeAsc(Long jobId, ReviewStatus reviewStatus) {
        return selectList(Wrappers.lambdaQuery(TermHit.class)
                .eq(TermHit::getJobId, jobId)
                .eq(TermHit::getReviewStatus, reviewStatus)
                .orderByAsc(TermHit::getStartTime));
    }

    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(TermHit.class).eq(TermHit::getJobId, jobId));
    }
}
