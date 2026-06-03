package com.ai.moderation.controller;

import com.ai.moderation.dto.*;
import com.ai.moderation.service.ClipSuggestionService;
import com.ai.moderation.service.ModerationQueryService;
import com.ai.moderation.service.TranscriptService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public TermHitResponse updateHitStatus(@PathVariable Long id, @RequestBody ModerationQueryService.HitStatusRequest request) {
        return queryService.updateHitStatus(id, request.status());
    }
}

