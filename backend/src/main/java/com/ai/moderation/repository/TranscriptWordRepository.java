package com.ai.moderation.repository;

import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface TranscriptWordRepository extends BaseCrudMapper<TranscriptWord> {
    default List<TranscriptWord> findByJobIdOrderBySequenceNoAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(TranscriptWord.class)
                .eq(TranscriptWord::getJobId, jobId)
                .orderByAsc(TranscriptWord::getSequenceNo));
    }

    default List<TranscriptWord> findBySegmentIdOrderBySequenceNoAsc(Long segmentId) {
        return selectList(Wrappers.lambdaQuery(TranscriptWord.class)
                .eq(TranscriptWord::getSegmentId, segmentId)
                .orderByAsc(TranscriptWord::getSequenceNo));
    }

    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(TranscriptWord.class).eq(TranscriptWord::getJobId, jobId));
    }
}
