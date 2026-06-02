package com.ai.moderation.repository;

import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface DetectionJobRepository extends BaseCrudMapper<DetectionJob> {
    default List<DetectionJob> findByVideoIdOrderByCreatedAtDesc(Long videoId) {
        return selectList(Wrappers.lambdaQuery(DetectionJob.class)
                .eq(DetectionJob::getVideoId, videoId)
                .orderByDesc(DetectionJob::getCreatedAt));
    }
}
