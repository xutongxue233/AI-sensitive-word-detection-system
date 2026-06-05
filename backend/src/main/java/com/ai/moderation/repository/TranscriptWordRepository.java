package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.TranscriptWord;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code transcript_words} 表读写,对应实体 {@link TranscriptWord}。
 *
 * <p>词级时间戳是命中定位到时间轴的关键:规则/AI 提取到命中词后,用词级起止时间确定剪辑区间。
 * 每个词归属一个转写段({@link com.ai.moderation.domain.TranscriptSegment}),在 TRANSCRIBING 阶段随段落一并落库。</p>
 */
public interface TranscriptWordRepository extends BaseCrudMapper<TranscriptWord> {

    /**
     * 取某任务全部词,按词序号升序(还原原文顺序)。
     *
     * @param jobId 检测任务 ID
     * @return 该任务的词列表
     */
    default List<TranscriptWord> findByJobIdOrderBySequenceNoAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(TranscriptWord.class)
                .eq(TranscriptWord::getJobId, jobId)
                .orderByAsc(TranscriptWord::getSequenceNo));
    }

    /**
     * 取某转写段下的全部词,按词序号升序。
     *
     * @param segmentId 转写段 ID
     * @return 该段的词列表
     */
    default List<TranscriptWord> findBySegmentIdOrderBySequenceNoAsc(Long segmentId) {
        return selectList(Wrappers.lambdaQuery(TranscriptWord.class)
                .eq(TranscriptWord::getSegmentId, segmentId)
                .orderByAsc(TranscriptWord::getSequenceNo));
    }

    /**
     * 删除某任务全部词,通常用于重跑任务前清理旧转写。
     *
     * @param jobId 检测任务 ID
     */
    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(TranscriptWord.class).eq(TranscriptWord::getJobId, jobId));
    }
}
