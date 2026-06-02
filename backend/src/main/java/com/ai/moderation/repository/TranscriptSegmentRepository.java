package com.ai.moderation.repository;

import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface TranscriptSegmentRepository extends BaseCrudMapper<TranscriptSegment> {
    default List<TranscriptSegment> findByJobIdOrderBySequenceNoAsc(Long jobId) {
        return selectList(Wrappers.lambdaQuery(TranscriptSegment.class)
                .eq(TranscriptSegment::getJobId, jobId)
                .orderByAsc(TranscriptSegment::getSequenceNo));
    }

    default void deleteByJobId(Long jobId) {
        delete(Wrappers.lambdaQuery(TranscriptSegment.class).eq(TranscriptSegment::getJobId, jobId));
    }
}
