package com.ai.moderation.service;

import com.ai.moderation.asr.SubtitleParser;
import com.ai.moderation.asr.TranscriptionResult;
import com.ai.moderation.asr.WhisperAsrClient;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;

@Service
public class DetectionPipelineService {
    private final DetectionJobRepository jobRepository;
    private final VideoFileRepository videoRepository;
    private final FfmpegService ffmpegService;
    private final SubtitleParser subtitleParser;
    private final WhisperAsrClient whisperAsrClient;
    private final TranscriptService transcriptService;
    private final RuleMatchingService ruleMatchingService;
    private final AiExtractionService aiExtractionService;
    private final ClipSuggestionService clipSuggestionService;

    public DetectionPipelineService(
            DetectionJobRepository jobRepository,
            VideoFileRepository videoRepository,
            FfmpegService ffmpegService,
            SubtitleParser subtitleParser,
            WhisperAsrClient whisperAsrClient,
            TranscriptService transcriptService,
            RuleMatchingService ruleMatchingService,
            AiExtractionService aiExtractionService,
            ClipSuggestionService clipSuggestionService
    ) {
        this.jobRepository = jobRepository;
        this.videoRepository = videoRepository;
        this.ffmpegService = ffmpegService;
        this.subtitleParser = subtitleParser;
        this.whisperAsrClient = whisperAsrClient;
        this.transcriptService = transcriptService;
        this.ruleMatchingService = ruleMatchingService;
        this.aiExtractionService = aiExtractionService;
        this.clipSuggestionService = clipSuggestionService;
    }

    @Async
    public void processAsync(Long jobId) {
        DetectionJob job = jobRepository.findById(jobId).orElseThrow();
        VideoFile video = videoRepository.findById(job.getVideoId()).orElseThrow();
        try {
            mark(job, JobStatus.EXTRACTING_AUDIO, 10);
            TranscriptionResult transcription = buildTranscript(job, video);

            mark(job, JobStatus.TRANSCRIBING, 35);
            transcriptService.replaceTranscript(job, transcription);

            mark(job, JobStatus.MATCHING_TERMS, 55);
            ruleMatchingService.matchJob(job);

            mark(job, JobStatus.AI_REVIEWING, 75);
            aiExtractionService.extractAndReview(job.getId());

            mark(job, JobStatus.SUGGESTING_CLIPS, 90);
            clipSuggestionService.createSuggestions(job.getId());

            job.setStatus(JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCompletedAt(Instant.now());
            video.setStatus(VideoStatus.DETECTED);
            videoRepository.save(video);
            jobRepository.save(job);
        } catch (Throwable ex) {
            job.setStatus(JobStatus.FAILED);
            job.setProgress(100);
            job.setErrorMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            job.setCompletedAt(Instant.now());
            video.setStatus(VideoStatus.FAILED);
            videoRepository.save(video);
            jobRepository.save(job);
        }
    }

    private TranscriptionResult buildTranscript(DetectionJob job, VideoFile video) throws Exception {
        if (video.getSubtitlePath() != null && !video.getSubtitlePath().isBlank()) {
            return subtitleParser.parse(Path.of(video.getSubtitlePath()));
        }
        Path videoPath = Path.of(video.getStoragePath());
        Path workDir = videoPath.getParent().resolve("job-" + job.getId());
        Path audioPath = ffmpegService.extractAudio(videoPath, workDir);
        return whisperAsrClient.transcribe(audioPath);
    }

    private void mark(DetectionJob job, JobStatus status, int progress) {
        job.setStatus(status);
        job.setProgress(progress);
        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        jobRepository.save(job);
    }
}
