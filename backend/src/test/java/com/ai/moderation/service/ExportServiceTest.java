package com.ai.moderation.service;

import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.repository.ClipSuggestionRepository;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportServiceTest {
    private VideoFileRepository videoRepository;
    private DetectionJobRepository jobRepository;
    private ClipSuggestionRepository suggestionRepository;
    private TermHitRepository hitRepository;
    private TranscriptSegmentRepository segmentRepository;
    private FfmpegService ffmpegService;
    private SettingsService settingsService;
    private ExportService service;
    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties(tempDir.toString());
        videoRepository = mock(VideoFileRepository.class);
        jobRepository = mock(DetectionJobRepository.class);
        suggestionRepository = mock(ClipSuggestionRepository.class);
        hitRepository = mock(TermHitRepository.class);
        segmentRepository = mock(TranscriptSegmentRepository.class);
        ffmpegService = mock(FfmpegService.class);
        settingsService = mock(SettingsService.class);
        service = new ExportService(
                storageProperties,
                videoRepository,
                jobRepository,
                suggestionRepository,
                hitRepository,
                segmentRepository,
                ffmpegService,
                settingsService
        );
    }

    @Test
    void subtitleMaskRangeUsesSuggestionTimeInsteadOfWholeSegmentTime() {
        VideoFile video = new VideoFile();
        video.setId(1L);
        video.setStoragePath(tempDir.resolve("input.mp4").toString());
        video.setDurationSeconds(20.0);
        DetectionJob job = new DetectionJob();
        job.setId(2L);
        job.setVideoId(1L);
        job.setStatus(JobStatus.COMPLETED);
        ClipSuggestion suggestion = new ClipSuggestion();
        suggestion.setId(3L);
        suggestion.setJobId(2L);
        suggestion.setHitId(4L);
        suggestion.setStatus(ClipStatus.CONFIRMED);
        suggestion.setStartTime(5.4);
        suggestion.setEndTime(5.9);
        TermHit hit = new TermHit();
        hit.setId(4L);
        hit.setJobId(2L);
        hit.setSegmentId(5L);
        hit.setSource(TranscriptSource.VIDEO_SUBTITLE);
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(5L);
        segment.setJobId(2L);
        segment.setStartTime(5.0);
        segment.setEndTime(8.0);
        segment.setBboxX(0.1);
        segment.setBboxY(0.7);
        segment.setBboxWidth(0.8);
        segment.setBboxHeight(0.12);
        Path exported = tempDir.resolve("exports/video-1/job-2/subtitle.mp4");

        when(videoRepository.findById(1L)).thenReturn(Optional.of(video));
        when(jobRepository.findByVideoIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(job));
        when(suggestionRepository.findByJobIdAndStatusOrderByStartTimeAsc(2L, ClipStatus.CONFIRMED))
                .thenReturn(List.of(suggestion));
        when(hitRepository.findByJobIdOrderByStartTimeAsc(2L)).thenReturn(List.of(hit));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(2L)).thenReturn(List.of(segment));
        when(ffmpegService.exportWithSubtitleBlur(any(), any(), anyList())).thenReturn(exported);

        service.exportConfirmedClips(1L);

        ArgumentCaptor<List<FfmpegService.SubtitleMaskRange>> captor = ArgumentCaptor.captor();
        verify(ffmpegService).exportWithSubtitleBlur(any(), any(), captor.capture());
        FfmpegService.SubtitleMaskRange range = captor.getValue().getFirst();
        assertThat(range.start()).isCloseTo(5.4, within(0.0001));
        assertThat(range.end()).isCloseTo(5.9, within(0.0001));
        assertThat(range.x()).isEqualTo(0.1);
        assertThat(range.y()).isEqualTo(0.7);
    }

    @Test
    void exportDeletesOldGeneratedFilesAfterNewOutputIsReady() throws IOException {
        VideoFile video = new VideoFile();
        video.setId(1L);
        video.setStoragePath(tempDir.resolve("input.mp4").toString());
        video.setDurationSeconds(20.0);
        DetectionJob job = new DetectionJob();
        job.setId(2L);
        job.setVideoId(1L);
        job.setStatus(JobStatus.COMPLETED);
        ClipSuggestion suggestion = new ClipSuggestion();
        suggestion.setId(3L);
        suggestion.setJobId(2L);
        suggestion.setHitId(4L);
        suggestion.setStatus(ClipStatus.CONFIRMED);
        suggestion.setStartTime(5.0);
        suggestion.setEndTime(8.0);
        TermHit hit = new TermHit();
        hit.setId(4L);
        hit.setJobId(2L);
        hit.setSegmentId(5L);
        hit.setSource(TranscriptSource.VIDEO_SUBTITLE);
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(5L);
        segment.setJobId(2L);
        segment.setStartTime(5.0);
        segment.setEndTime(8.0);

        Path outputDir = tempDir.resolve("exports/video-1/job-2");
        Files.createDirectories(outputDir);
        Path oldFinal = Files.writeString(outputDir.resolve("moderated-old.mp4"), "old");
        Path oldSubtitle = Files.writeString(outputDir.resolve("subtitle-moderated-old.mp4"), "old");
        Path oldKeep = Files.writeString(outputDir.resolve("keep-0.mp4"), "old");
        Path oldConcat = Files.writeString(outputDir.resolve("concat.txt"), "old");
        Path currentFinal = Files.writeString(outputDir.resolve("subtitle-moderated-new.mp4"), "new");
        Path oldJobDir = tempDir.resolve("exports/video-1/job-1");
        Files.createDirectories(oldJobDir);
        Path oldJobFinal = Files.writeString(oldJobDir.resolve("moderated-old-job.mp4"), "old");

        when(videoRepository.findById(1L)).thenReturn(Optional.of(video));
        when(jobRepository.findByVideoIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(job));
        when(suggestionRepository.findByJobIdAndStatusOrderByStartTimeAsc(2L, ClipStatus.CONFIRMED))
                .thenReturn(List.of(suggestion));
        when(hitRepository.findByJobIdOrderByStartTimeAsc(2L)).thenReturn(List.of(hit));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(2L)).thenReturn(List.of(segment));
        when(ffmpegService.exportWithSubtitleBlur(any(), any(), anyList())).thenReturn(currentFinal);

        service.exportConfirmedClips(1L);

        assertThat(currentFinal).exists();
        assertThat(oldFinal).doesNotExist();
        assertThat(oldSubtitle).doesNotExist();
        assertThat(oldKeep).doesNotExist();
        assertThat(oldConcat).doesNotExist();
        assertThat(oldJobFinal).doesNotExist();
        assertThat(oldJobDir).doesNotExist();
    }
}
