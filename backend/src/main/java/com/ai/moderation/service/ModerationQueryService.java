package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.dto.JobResponse;
import com.ai.moderation.dto.TermHitResponse;
import com.ai.moderation.dto.TimelineItemResponse;
import com.ai.moderation.repository.AiReviewRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 审核只读查询门面:聚合任务详情、命中列表、违规时间轴的查询,以及人工改命中状态。
 *
 * <p>{@link #timeline} 只返回 {@link ReviewStatus#VIOLATION} 或 {@link ReviewStatus#CONFIRMED}
 * 的命中——即"AI 判违规待确认"与"人工已确认违规"两类,SAFE/低置信命中不进时间轴。
 */
@Service
public class ModerationQueryService {
    private final DetectionJobRepository jobRepository;
    private final TermHitRepository hitRepository;
    private final AiReviewRepository reviewRepository;

    public ModerationQueryService(
            DetectionJobRepository jobRepository,
            TermHitRepository hitRepository,
            AiReviewRepository reviewRepository
    ) {
        this.jobRepository = jobRepository;
        this.hitRepository = hitRepository;
        this.reviewRepository = reviewRepository;
    }

    /** 查询任务详情(状态/进度等),不存在抛 404。 */
    @Transactional(readOnly = true)
    public JobResponse getJob(Long jobId) {
        return JobResponse.from(jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "检测任务不存在")));
    }

    /** 列出某任务全部命中(含各自的 AI 复核结论,无复核则为 null),按起始时间升序。 */
    @Transactional(readOnly = true)
    public List<TermHitResponse> listHits(Long jobId) {
        return hitRepository.findByJobIdOrderByStartTimeAsc(jobId)
                .stream()
                .map(hit -> TermHitResponse.from(hit, reviewRepository.findByHitId(hit.getId()).orElse(null)))
                .toList();
    }

    /**
     * 违规时间轴:只取 {@link ReviewStatus#VIOLATION}/{@link ReviewStatus#CONFIRMED} 命中,
     * 携带其 AI 复核理由,供前端在时间轴上标注可定位的违规时段。
     *
     * @param jobId 检测任务 id
     * @return 按起始时间升序的时间轴条目
     */
    @Transactional(readOnly = true)
    public List<TimelineItemResponse> timeline(Long jobId) {
        return hitRepository.findByJobIdOrderByStartTimeAsc(jobId)
                .stream()
                .filter(hit -> hit.getReviewStatus() == ReviewStatus.VIOLATION || hit.getReviewStatus() == ReviewStatus.CONFIRMED)
                .map(hit -> new TimelineItemResponse(
                        hit.getId(),
                        hit.getMatchedText(),
                        hit.getCategory(),
                        hit.getSeverity(),
                        hit.getSource(),
                        hit.getReviewStatus(),
                        hit.getStartTime(),
                        hit.getEndTime(),
                        hit.getContextText(),
                        hit.getAiConfidence(),
                        reviewRepository.findByHitId(hit.getId()).map(AiReview::getReason).orElse(null)
                ))
                .toList();
    }

    /**
     * 人工改写单条命中的复核状态(确认违规/标记安全等)。
     *
     * @param hitId  命中记录 id
     * @param status 人工设定的目标状态
     * @return 更新后的命中响应(含其 AI 复核结论)
     */
    @Transactional
    public TermHitResponse updateHitStatus(Long hitId, ReviewStatus status) {
        TermHit hit = hitRepository.findById(hitId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "命中记录不存在"));
        hit.setReviewStatus(status);
        TermHit saved = hitRepository.save(hit);
        return TermHitResponse.from(saved, reviewRepository.findByHitId(hitId).orElse(null));
    }
}
