package com.ai.moderation.controller;

import com.ai.moderation.dto.ClipSuggestionResponse;
import com.ai.moderation.dto.ClipSuggestionUpdateRequest;
import com.ai.moderation.dto.HitStatusRequest;
import com.ai.moderation.dto.JobResponse;
import com.ai.moderation.dto.TermHitResponse;
import com.ai.moderation.dto.TimelineItemResponse;
import com.ai.moderation.dto.TranscriptSegmentResponse;
import com.ai.moderation.service.ClipSuggestionService;
import com.ai.moderation.service.ModerationQueryService;
import com.ai.moderation.service.TranscriptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检测任务结果查询的 REST 入口,统一前缀 {@code /api/v1}。
 *
 * <p>聚合 {@link ModerationQueryService}(任务详情、时间轴、命中、命中状态改判)、
 * {@link TranscriptService}(转写段落)与 {@link ClipSuggestionService}(剪辑建议增改),
 * 是前端审核工作台轮询任务进度、展示与人工确认结果的主要数据来源。
 *
 * <p>位于检测管线之后:管线由 {@code DetectionPipelineService} 异步跑完落库,
 * 本控制器只做只读查询与人工复核态的局部更新,不触发新的检测计算。
 */
@RestController
@RequestMapping("/api/v1")
public class JobController {
    private final ModerationQueryService queryService;
    private final TranscriptService transcriptService;
    private final ClipSuggestionService clipSuggestionService;

    public JobController(
            ModerationQueryService queryService,
            TranscriptService transcriptService,
            ClipSuggestionService clipSuggestionService
    ) {
        this.queryService = queryService;
        this.transcriptService = transcriptService;
        this.clipSuggestionService = clipSuggestionService;
    }

    @GetMapping("/jobs/{id}")
    public JobResponse getJob(@PathVariable Long id) {
        return queryService.getJob(id);
    }

    @GetMapping("/jobs/{id}/segments")
    public List<TranscriptSegmentResponse> listSegments(@PathVariable Long id) {
        return transcriptService.listSegments(id);
    }

    @GetMapping("/jobs/{id}/timeline")
    public List<TimelineItemResponse> timeline(@PathVariable Long id) {
        return queryService.timeline(id);
    }

    @GetMapping("/jobs/{id}/hits")
    public List<TermHitResponse> hits(@PathVariable Long id) {
        return queryService.listHits(id);
    }

    @GetMapping("/jobs/{id}/clip-suggestions")
    public List<ClipSuggestionResponse> listSuggestions(@PathVariable Long id) {
        return clipSuggestionService.listSuggestions(id);
    }

    @PostMapping("/jobs/{id}/clip-suggestions")
    public List<ClipSuggestionResponse> createSuggestions(@PathVariable Long id) {
        return clipSuggestionService.createSuggestions(id);
    }

    @PatchMapping("/clip-suggestions/{id}")
    public ClipSuggestionResponse updateSuggestion(@PathVariable Long id, @RequestBody ClipSuggestionUpdateRequest request) {
        return clipSuggestionService.updateSuggestion(id, request);
    }

    @PatchMapping("/hits/{id}/review-status")
    public TermHitResponse updateHitStatus(@PathVariable Long id, @RequestBody HitStatusRequest request) {
        return queryService.updateHitStatus(id, request.status());
    }
}

