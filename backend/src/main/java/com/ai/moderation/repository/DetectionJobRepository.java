package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.DetectionJob;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code detection_jobs} 表读写,对应实体 {@link DetectionJob}。
 *
 * <p>检测任务是整条管线(抽音频→转写→规则召回→AI 复核→剪辑建议)的执行实例载体,
 * 记录任务状态与进度百分比,供前端轮询展示。一个视频可发起多次检测任务。</p>
 */
public interface DetectionJobRepository extends BaseCrudMapper<DetectionJob> {

    /**
     * 取某视频的全部检测任务,按创建时间倒序(最新任务在前)。
     *
     * @param videoId 视频 ID
     * @return 该视频的检测任务列表
     */
    default List<DetectionJob> findByVideoIdOrderByCreatedAtDesc(Long videoId) {
        return selectList(Wrappers.lambdaQuery(DetectionJob.class)
                .eq(DetectionJob::getVideoId, videoId)
                .orderByDesc(DetectionJob::getCreatedAt));
    }

    /**
     * 删除某视频的全部检测任务,通常用于删除视频时级联清理。
     *
     * @param videoId 视频 ID
     */
    default void deleteByVideoId(Long videoId) {
        delete(Wrappers.lambdaQuery(DetectionJob.class).eq(DetectionJob::getVideoId, videoId));
    }
}
