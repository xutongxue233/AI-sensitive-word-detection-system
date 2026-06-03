package com.ai.moderation.service;

import com.ai.moderation.asr.SubtitleParser;
import com.ai.moderation.asr.SubtitleOcrClient;
import com.ai.moderation.asr.TranscriptionMerger;
import com.ai.moderation.asr.TranscriptionResult;
import com.ai.moderation.asr.WhisperAsrClient;
import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;
import com.ai.moderation.domain.TranscriptSource;
import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;
import com.ai.moderation.repository.DetectionJobRepository;
import com.ai.moderation.repository.VideoFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DetectionPipelineService {
    private static final Logger log = LoggerFactory.getLogger(DetectionPipelineService.class);

    private final DetectionJobRepository jobRepository;
    private final VideoFileRepository videoRepository;
    private final FfmpegService ffmpegService;
    private final SubtitleParser subtitleParser;
    private final SubtitleOcrClient subtitleOcrClient;
    private final WhisperAsrClient whisperAsrClient;
    private final TranscriptionMerger transcriptionMerger;
    private final TranscriptService transcriptService;
    private final RuleMatchingService ruleMatchingService;
    private final AiExtractionService aiExtractionService;
    private final ClipSuggestionService clipSuggestionService;

    public DetectionPipelineService(
            DetectionJobRepository jobRepository,
            VideoFileRepository videoRepository,
            FfmpegService ffmpegService,
            SubtitleParser subtitleParser,
            SubtitleOcrClient subtitleOcrClient,
            WhisperAsrClient whisperAsrClient,
            TranscriptionMerger transcriptionMerger,
            TranscriptService transcriptService,
            RuleMatchingService ruleMatchingService,
            AiExtractionService aiExtractionService,
            ClipSuggestionService clipSuggestionService
    ) {
        this.jobRepository = jobRepository;
        this.videoRepository = videoRepository;
        this.ffmpegService = ffmpegService;
        this.subtitleParser = subtitleParser;
        this.subtitleOcrClient = subtitleOcrClient;
        this.whisperAsrClient = whisperAsrClient;
        this.transcriptionMerger = transcriptionMerger;
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
        Path videoPath = Path.of(video.getStoragePath());
        List<TranscriptionResult> layers = new ArrayList<>();

        TranscriptionResult audioTranscript = null;
        if (whisperAsrClient.enabled()) {
            Path workDir = videoPath.getParent().resolve("job-" + job.getId());
            Path audioPath = ffmpegService.extractAudio(videoPath, workDir);
            audioTranscript = whisperAsrClient.transcribe(audioPath);
            layers.add(audioTranscript);
        }

        if (video.getSubtitlePath() != null && !video.getSubtitlePath().isBlank()) {
            layers.add(subtitleParser.parse(Path.of(video.getSubtitlePath()), TranscriptSource.SUBTITLE_FILE));
        } else {
            layers.add(recognizeSubtitles(videoPath, hasSegments(audioTranscript)));
        }

        TranscriptionResult merged = transcriptionMerger.merge(layers);
        if (!hasSegments(merged)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "未获得有效音频转写或画面字幕 OCR 结果");
        }
        return merged;
    }

    private TranscriptionResult recognizeSubtitles(Path videoPath, boolean audioAvailable) {
        if (!subtitleOcrClient.enabled()) {
            return new TranscriptionResult(List.of());
        }
        try {
            return subtitleOcrClient.recognize(videoPath);
        } catch (RuntimeException ex) {
            if (!audioAvailable) {
                throw ex;
            }
            log.warn("画面字幕 OCR 失败，已继续使用音频转写结果: {}", ex.getMessage());
            return new TranscriptionResult(List.of());
        }
    }

    private boolean hasSegments(TranscriptionResult result) {
        return result != null && result.segments() != null && !result.segments().isEmpty();
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
