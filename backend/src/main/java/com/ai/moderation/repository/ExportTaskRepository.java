package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.ExportTask;
import com.ai.moderation.domain.ExportTaskStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@code export_tasks} 表读写。
 */
public interface ExportTaskRepository extends BaseCrudMapper<ExportTask> {

    default List<ExportTask> findByVideoIdOrderByCreatedAtDesc(Long videoId) {
        return selectList(Wrappers.lambdaQuery(ExportTask.class)
                .eq(ExportTask::getVideoId, videoId)
                .orderByDesc(ExportTask::getCreatedAt)
                .orderByDesc(ExportTask::getId));
    }

    default List<ExportTask> findByStatusInOrderByCreatedAtAsc(Collection<ExportTaskStatus> statuses) {
        return selectList(Wrappers.lambdaQuery(ExportTask.class)
                .in(ExportTask::getStatus, statuses)
                .orderByAsc(ExportTask::getCreatedAt)
                .orderByAsc(ExportTask::getId));
    }

    default Optional<ExportTask> findActiveByVideoId(Long videoId) {
        return selectList(Wrappers.lambdaQuery(ExportTask.class)
                .eq(ExportTask::getVideoId, videoId)
                .in(ExportTask::getStatus, List.of(ExportTaskStatus.QUEUED, ExportTaskStatus.RUNNING))
                .orderByDesc(ExportTask::getCreatedAt)
                .orderByDesc(ExportTask::getId)
                .last("LIMIT 1"))
                .stream()
                .findFirst();
    }
}
