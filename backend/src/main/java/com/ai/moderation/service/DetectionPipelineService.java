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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 检测管线编排器:整个视频审核流程的中枢。
 *
 * <p>{@link #processAsync} 以 {@code @Async} 异步执行,串起六个阶段——抽音频转写、落库转写、规则召回、
 * AI 复核、剪辑建议、收尾——每阶段都通过 {@link #mark} 写 {@link JobStatus} 与进度百分比,供前端轮询展示。
 *
 * <p>关键设计:
 * <ul>
 *   <li>音画两腿并行集中在 {@link #buildTranscript}——音频腿(Whisper)失败即任务失败,画面 OCR 腿失败降级为空。
 *   <li>任一阶段抛异常即把整个任务置为 {@link JobStatus#FAILED},不做部分成功。
 *   <li>{@code finally} 始终调 {@link #cleanupWorkDir} 清理 {@code job-{id}} 临时目录(主要是抽出的 audio.wav),
 *       无论成败都不残留中间产物。
 * </ul>
 *
 * 由 {@link VideoService#createDetectionJob} 在创建任务后触发。
 */
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

    // 音画两腿并行的专用线程池:故意不注册为 Spring Bean,避免触发 applicationTaskExecutor 的
    // @ConditionalOnMissingBean(Executor.class) 退避,从而保证 @Async processAsync 仍用框架默认执行器,
    // 与本池彻底分离——否则 processAsync 与其子任务同池,join 等待子任务会自饥饿死锁。
    private final ExecutorService transcriptExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("transcript-" + thread.threadId());
        thread.setDaemon(true);
        return thread;
    });

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

    /** 容器销毁时优雅关闭转写线程池,避免守护线程残留。 */
    @PreDestroy
    public void shutdownTranscriptExecutor() {
        transcriptExecutor.shutdown();
    }

    /**
     * 异步执行整条检测管线:抽音频转写 → 落库 → 规则召回 → AI 复核 → 剪辑建议 → 收尾。
     *
     * <p>每个阶段先 {@link #mark} 写状态与进度;全程顺利则置 {@link JobStatus#COMPLETED}+视频 {@link VideoStatus#DETECTED};
     * 任一阶段抛出任意 {@link Throwable} 即整任务 {@link JobStatus#FAILED}+视频 {@link VideoStatus#FAILED} 并记录根因;
     * 无论成败,{@code finally} 都清理 {@code job-{id}} 临时目录。
     *
     * @param jobId 待处理的检测任务 id,由 {@link VideoService#createDetectionJob} 创建并传入
     */
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
        } finally {
            // 检测产生的音频(job-{id}/audio.wav)是中间产物,无论成败都清理,避免累积占用磁盘
            cleanupWorkDir(video, jobId);
        }
    }

    /** 删除本次检测的临时工作目录 {@code job-{id}}(主要是抽出的 audio.wav);失败只记日志不影响主流程。 */
    private void cleanupWorkDir(VideoFile video, Long jobId) {
        if (video.getStoragePath() == null) {
            return;
        }
        Path parent = Path.of(video.getStoragePath()).getParent();
        if (parent == null) {
            return;
        }
        Path workDir = parent.resolve("job-" + jobId);
        if (!Files.isDirectory(workDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("清理检测临时文件失败 {}: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("清理检测工作目录失败 jobId={}: {}", jobId, ex.getMessage());
        }
    }

    /**
     * 构建转写结果:音频腿与画面腿并行,合并去重后返回。
     *
     * <p>两腿在 {@link #transcriptExecutor} 上并行:
     * <ul>
     *   <li>腿A 音频:FFmpeg 抽 16k mono wav → Whisper 转写,失败即任务失败(不降级)。
     *   <li>腿B 画面:仅当无外部字幕文件时跑 OCR,失败经 {@code handle} 降级为空,不拖垮音频腿。
     * </ul>
     * 有外部 {@code .srt/.vtt} 时跳过腿B,改用 {@link SubtitleParser} 解析。各层经 {@link TranscriptionMerger#merge}
     * 按来源+时间重叠去重合并;若合并后无任何片段则抛 {@link ApiException}(若 OCR 曾失败附上根因便于排障)。
     *
     * @param job   当前检测任务,用于推导 {@code job-{id}} 工作目录
     * @param video 视频记录,提供存储路径与可选外部字幕路径
     * @return 合并后的转写结果(至少含一条片段)
     * @throws Exception 音频腿异常或两腿均无有效结果时抛出
     */
    private TranscriptionResult buildTranscript(DetectionJob job, VideoFile video) throws Exception {
        Path videoPath = Path.of(video.getStoragePath());

        // 腿A:抽音频 + Whisper 转写,与画面 OCR 并行;音频腿失败=任务失败(不降级)
        CompletableFuture<TranscriptionResult> audioFuture = whisperAsrClient.enabled()
                ? CompletableFuture.supplyAsync(() -> {
                    Path workDir = videoPath.getParent().resolve("job-" + job.getId());
                    Path audioPath = ffmpegService.extractAudio(videoPath, workDir);
                    return whisperAsrClient.transcribe(audioPath);
                }, transcriptExecutor)
                : CompletableFuture.completedFuture(null);

        // 腿B:画面硬字幕 OCR(仅无外部字幕文件时),与音频并行;OCR 腿失败降级为空,不拖垮音频结果
        AtomicReference<String> ocrFailure = new AtomicReference<>();
        boolean useSubtitleFile = video.getSubtitlePath() != null && !video.getSubtitlePath().isBlank();
        CompletableFuture<TranscriptionResult> ocrFuture = useSubtitleFile
                ? CompletableFuture.completedFuture(new TranscriptionResult(List.of()))
                : CompletableFuture.supplyAsync(() -> {
                    if (!subtitleOcrClient.enabled()) {
                        return new TranscriptionResult(List.of());
                    }
                    return subtitleOcrClient.recognize(videoPath);
                }, transcriptExecutor).handle((result, ex) -> {
                    if (ex != null) {
                        String reason = rootMessage(ex);
                        ocrFailure.set(reason);
                        log.warn("画面字幕 OCR 失败，已降级为仅音频结果: {}", reason);
                        return new TranscriptionResult(List.of());
                    }
                    return result;
                });

        // 先 join 音频腿,让其异常自然冒泡=任务失败;OCR 腿已被 handle 兜底为空,不会抛
        List<TranscriptionResult> layers = new ArrayList<>();
        TranscriptionResult audioTranscript;
        try {
            audioTranscript = audioFuture.join();
        } catch (CompletionException ex) {
            throw unwrap(ex);
        }
        if (audioTranscript != null) {
            layers.add(audioTranscript);
        }

        if (useSubtitleFile) {
            layers.add(subtitleParser.parse(Path.of(video.getSubtitlePath()), TranscriptSource.SUBTITLE_FILE));
        } else {
            layers.add(ocrFuture.join());
        }

        TranscriptionResult merged = transcriptionMerger.merge(layers);
        // 兜底:两腿都无有效结果(等价于原"无音频且 OCR 失败=失败");若 OCR 曾失败则附上根因便于排障
        if (!hasSegments(merged)) {
            String ocrReason = ocrFailure.get();
            String message = ocrReason == null
                    ? "未获得有效音频转写或画面字幕 OCR 结果"
                    : "未获得有效音频转写或画面字幕 OCR 结果(画面 OCR 失败: " + ocrReason + ")";
            throw new ApiException(HttpStatus.BAD_GATEWAY, message);
        }
        return merged;
    }

    /** 把 CompletableFuture 包裹的 CompletionException 还原为原始运行时异常,保留可读的 errorMessage。 */
    private RuntimeException unwrap(CompletionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        String message = cause == null
                ? ex.getMessage()
                : (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        return new ApiException(HttpStatus.BAD_GATEWAY, message);
    }

    /** 取异常根因消息(展开 CompletionException 的 cause),无消息则回退到异常简单类名。 */
    private String rootMessage(Throwable ex) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private boolean hasSegments(TranscriptionResult result) {
        return result != null && result.segments() != null && !result.segments().isEmpty();
    }

    /** 写入任务阶段状态与进度;首次进入(startedAt 为空)时记录开始时间。 */
    private void mark(DetectionJob job, JobStatus status, int progress) {
        job.setStatus(status);
        job.setProgress(progress);
        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        jobRepository.save(job);
    }
}
