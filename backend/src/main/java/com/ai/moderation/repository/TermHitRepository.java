package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code term_hits} 表读写,对应实体 {@link TermHit}。
 *
 * <p>命中记录是规则召回与 AI 提取的共同产物,带来源({@code AUDIO}/{@code VIDEO_SUBTITLE})、
 * 复核状态({@link ReviewStatus})与 AI 置信度。在管线中由规则匹配/AI 审核阶段写入,
 * 查询阶段供时间轴展示,导出阶段据来源决定剪音频或去字幕。</p>
 */
public interface TermHitRepository extends BaseCrudMapper<TermHit> {

    /**
     * 取某任务全部命中,按起始时间升序(便于按时间轴顺序展示)。
     *
     * @param jobId 检测任务 ID
     * @return 该任务的命中列表
     */
    default List<TermHit> findByJobIdOrderByStartTimeAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(TermHit.class)
                .eq(TermHit::getJobId, jobId)
                .orderByAsc(TermHit::getStartTime));
    }

    /**
     * 取某任务指定复核状态的命中,按起始时间升序。
     *
     * <p>典型用于以 {@link ReviewStatus#VIOLATION} 过滤,取确认违规的命中进时间轴与剪辑建议。</p>
     *
     * @param jobId        检测任务 ID
     * @param reviewStatus 复核状态过滤条件
     * @return 命中状态的命中列表
     */
    default List<TermHit> findByJobIdAndReviewStatusOrderByStartTimeAsc(Long jobId, ReviewStatus reviewStatus) {
        return selectList(Wrappers.lambdaQuery(TermHit.class)
                .eq(TermHit::getJobId, jobId)
                .eq(TermHit::getReviewStatus, reviewStatus)
                .orderByAsc(TermHit::getStartTime));
    }

    /**
     * 删除某任务全部命中,通常用于重跑任务前清理旧结果。
     *
     * @param jobId 检测任务 ID
     */
    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(TermHit.class).eq(TermHit::getJobId, jobId));
    }
}
