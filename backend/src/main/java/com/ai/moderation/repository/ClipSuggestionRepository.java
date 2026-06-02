package com.ai.moderation.repository;

import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface ClipSuggestionRepository extends BaseCrudMapper<ClipSuggestion> {
    default List<ClipSuggestion> findByJobIdOrderByStartTimeAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(ClipSuggestion.class)
                .eq(ClipSuggestion::getJobId, jobId)
                .orderByAsc(ClipSuggestion::getStartTime));
    }

    default List<ClipSuggestion> findByJobIdAndStatusOrderByStartTimeAsc(Long jobId, ClipStatus status) {
        return selectList(Wrappers.lambdaQuery(ClipSuggestion.class)
                .eq(ClipSuggestion::getJobId, jobId)
                .eq(ClipSuggestion::getStatus, status)
                .orderByAsc(ClipSuggestion::getStartTime));
    }

    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(ClipSuggestion.class).eq(ClipSuggestion::getJobId, jobId));
    }
}
