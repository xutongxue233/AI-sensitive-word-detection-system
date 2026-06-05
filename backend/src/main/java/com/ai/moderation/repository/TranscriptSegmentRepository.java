package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.TranscriptSegment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code transcript_segments} 表读写,对应实体 {@link TranscriptSegment}。
 *
 * <p>转写段落是音频 Whisper 与画面 OCR 两腿合并后的句级文本载体,带来源标记与
 * 字幕框 bbox(归一化 0~1,供导出去字幕定位)。在 TRANSCRIBING 阶段落库,
 * 供后续规则匹配、AI 复核与前端字幕轴展示。</p>
 */
public interface TranscriptSegmentRepository extends BaseCrudMapper<TranscriptSegment> {

    /**
     * 取某任务全部转写段落,按段序号升序(还原原文顺序)。
     *
     * @param jobId 检测任务 ID
     * @return 该任务的转写段落列表
     */
    default List<TranscriptSegment> findByJobIdOrderBySequenceNoAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(TranscriptSegment.class)
                .eq(TranscriptSegment::getJobId, jobId)
                .orderByAsc(TranscriptSegment::getSequenceNo));
    }

    /**
     * 删除某任务全部转写段落,通常用于重跑任务前清理旧转写。
     *
     * @param jobId 检测任务 ID
     */
    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(TranscriptSegment.class).eq(TranscriptSegment::getJobId, jobId));
    }
}
