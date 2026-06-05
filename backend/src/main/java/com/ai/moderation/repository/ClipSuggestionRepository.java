package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code clip_suggestions} 表读写,对应实体 {@link ClipSuggestion}。
 *
 * <p>在检测管线 SUGGESTING_CLIPS 阶段写入剪辑/去字幕建议;查询阶段供前端时间轴展示,
 * 导出阶段据其状态({@link ClipStatus})决定是否对该时段执行剪除或字幕去除。</p>
 */
public interface ClipSuggestionRepository extends BaseCrudMapper<ClipSuggestion> {

    /**
     * 取某任务全部剪辑建议,按起始时间升序(便于按时间轴顺序展示)。
     *
     * @param jobId 检测任务 ID
     * @return 该任务的剪辑建议列表
     */
    default List<ClipSuggestion> findByJobIdOrderByStartTimeAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(ClipSuggestion.class)
                .eq(ClipSuggestion::getJobId, jobId)
                .orderByAsc(ClipSuggestion::getStartTime));
    }

    /**
     * 取某任务指定状态的剪辑建议,按起始时间升序;导出时常用于筛选已确认状态的建议。
     *
     * @param jobId  检测任务 ID
     * @param status 剪辑状态过滤条件
     * @return 命中状态的剪辑建议列表
     */
    default List<ClipSuggestion> findByJobIdAndStatusOrderByStartTimeAsc(Long jobId, ClipStatus status) {
        return selectList(Wrappers.lambdaQuery(ClipSuggestion.class)
                .eq(ClipSuggestion::getJobId, jobId)
                .eq(ClipSuggestion::getStatus, status)
                .orderByAsc(ClipSuggestion::getStartTime));
    }

    /**
     * 删除某任务全部剪辑建议,通常用于重跑任务前清理旧结果。
     *
     * @param jobId 检测任务 ID
     */
    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(ClipSuggestion.class).eq(ClipSuggestion::getJobId, jobId));
    }
}
