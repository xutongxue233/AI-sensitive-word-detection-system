package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.ClipProperties;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.dto.ClipSuggestionResponse;
import com.ai.moderation.dto.ManualClipSuggestionRequest;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClipSuggestionServiceTest {
    private ClipSuggestionRepository suggestionRepository;
    private TermHitRepository hitRepository;
    private TranscriptSegmentRepository segmentRepository;
    private DetectionJobRepository jobRepository;
    private SettingsService settingsService;
    private ClipSuggestionService service;
    private final AtomicReference<TermHit> savedHit = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(ClipSuggestionRepository.class);
        hitRepository = mock(TermHitRepository.class);
        segmentRepository = mock(TranscriptSegmentRepository.class);
        jobRepository = mock(DetectionJobRepository.class);
        settingsService = mock(SettingsService.class);
        service = new ClipSuggestionService(
                suggestionRepository, hitRepository, segmentRepository, jobRepository, settingsService);

        when(settingsService.currentClip()).thenReturn(new ClipProperties(0.2, false));
        when(hitRepository.save(any(TermHit.class))).thenAnswer(invocation -> {
            TermHit hit = invocation.getArgument(0);
            hit.setId(99L);
            savedHit.set(hit);
            return hit;
        });
        when(hitRepository.findById(99L)).thenAnswer(invocation -> Optional.ofNullable(savedHit.get()));
        when(suggestionRepository.save(any(ClipSuggestion.class))).thenAnswer(invocation -> {
            ClipSuggestion suggestion = invocation.getArgument(0);
            suggestion.setId(88L);
            return suggestion;
        });
        when(suggestionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ClipSuggestion> suggestions = invocation.getArgument(0);
            long id = 1;
            for (ClipSuggestion suggestion : suggestions) {
                suggestion.setId(id++);
            }
            return suggestions;
        });
    }

    @Test
    void createManualSuggestionCreatesHitAndPendingSuggestion() {
        DetectionJob job = job(1L);
        TranscriptSegment segment = segment(10L, 1L, TranscriptSource.VIDEO_SUBTITLE);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(segmentRepository.findById(10L)).thenReturn(Optional.of(segment));

        ClipSuggestionResponse response = service.createManualSuggestion(
                1L,
                new ManualClipSuggestionRequest(10L, " 全网最低 ", 5.1, 5.8)
        );

        TermHit hit = savedHit.get();
        assertThat(hit.getMatchedText()).isEqualTo("全网最低");
        assertThat(hit.getCategory()).isEqualTo("人工添加");
        assertThat(hit.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(hit.getRuleSource()).isEqualTo(MatchType.EXACT);
        assertThat(hit.getSource()).isEqualTo(TranscriptSource.VIDEO_SUBTITLE);
        assertThat(hit.getReviewStatus()).isEqualTo(ReviewStatus.VIOLATION);
        assertThat(hit.getAiConfidence()).isEqualTo(1.0);

        assertThat(response.id()).isEqualTo(88L);
        assertThat(response.hitId()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo(ClipStatus.PENDING);
        assertThat(response.action()).isEqualTo("BLUR_SUBTITLE");
        assertThat(response.startTime()).isCloseTo(4.8, within(0.0001));
        assertThat(response.endTime()).isCloseTo(8.2, within(0.0001));
        assertThat(response.segmentStartTime()).isEqualTo(5.0);
        assertThat(response.segmentEndTime()).isEqualTo(8.0);
    }

    @Test
    void createSuggestionsExpandsSubtitleHitsToWholeSegment() {
        DetectionJob job = job(1L);
        TranscriptSegment segment = segment(10L, 1L, TranscriptSource.VIDEO_SUBTITLE);
        TermHit subtitleHit = hit(31L, 1L, 10L, TranscriptSource.VIDEO_SUBTITLE, 5.4, 5.7);
        TermHit audioHit = hit(32L, 1L, 11L, TranscriptSource.AUDIO, 12.0, 12.3);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(segmentRepository.findById(10L)).thenReturn(Optional.of(segment));
        when(hitRepository.findByJobIdAndReviewStatusOrderByStartTimeAsc(1L, ReviewStatus.VIOLATION))
                .thenReturn(List.of(subtitleHit, audioHit));
        when(hitRepository.findById(31L)).thenReturn(Optional.of(subtitleHit));
        when(hitRepository.findById(32L)).thenReturn(Optional.of(audioHit));

        List<ClipSuggestionResponse> responses = service.createSuggestions(1L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).startTime()).isCloseTo(4.8, within(0.0001));
        assertThat(responses.get(0).endTime()).isCloseTo(8.2, within(0.0001));
        assertThat(responses.get(0).segmentStartTime()).isEqualTo(5.0);
        assertThat(responses.get(0).segmentEndTime()).isEqualTo(8.0);
        assertThat(responses.get(1).startTime()).isCloseTo(11.8, within(0.0001));
        assertThat(responses.get(1).endTime()).isCloseTo(12.5, within(0.0001));
        assertThat(responses.get(1).segmentStartTime()).isNull();
        assertThat(responses.get(1).segmentEndTime()).isNull();
    }

    @Test
    void createManualSuggestionRejectsSegmentFromAnotherJob() {
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job(1L)));
        when(segmentRepository.findById(10L)).thenReturn(Optional.of(segment(10L, 2L, TranscriptSource.AUDIO)));

        assertThatThrownBy(() -> service.createManualSuggestion(
                1L,
                new ManualClipSuggestionRequest(10L, "词", 1.0, 1.2)
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("转写句段不属于当前检测任务");
    }

    private DetectionJob job(Long id) {
        DetectionJob job = new DetectionJob();
        job.setId(id);
        return job;
    }

    private TranscriptSegment segment(Long id, Long jobId, TranscriptSource source) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(id);
        segment.setJobId(jobId);
        segment.setSource(source);
        segment.setText("这款产品全网最低");
        segment.setStartTime(5.0);
        segment.setEndTime(8.0);
        return segment;
    }

    private TermHit hit(Long id, Long jobId, Long segmentId, TranscriptSource source, double startTime, double endTime) {
        TermHit hit = new TermHit();
        hit.setId(id);
        hit.setJobId(jobId);
        hit.setSegmentId(segmentId);
        hit.setSource(source);
        hit.setMatchedText(source == TranscriptSource.AUDIO ? "音频词" : "字幕词");
        hit.setStartTime(startTime);
        hit.setEndTime(endTime);
        hit.setReviewStatus(ReviewStatus.VIOLATION);
        return hit;
    }
}
