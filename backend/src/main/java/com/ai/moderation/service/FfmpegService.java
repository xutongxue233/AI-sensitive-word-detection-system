package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.FfmpegProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FfmpegService {
    private final FfmpegProperties properties;

    public FfmpegService(FfmpegProperties properties) {
        this.properties = properties;
    }

    public Path extractAudio(Path videoPath, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            Path audioPath = outputDir.resolve("audio.wav");
            run(List.of(
                    properties.ffmpegPath(), "-y",
                    "-i", videoPath.toString(),
                    "-vn", "-ac", "1", "-ar", "16000",
                    audioPath.toString()
            ));
            return audioPath;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "抽取音频失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
    }

    public Double probeDuration(Path videoPath) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    properties.ffprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath.toString()
            );
            Process process = builder.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                return null;
            }
            return Double.parseDouble(output);
        } catch (Exception ex) {
            return null;
        }
    }

    public Path exportWithoutClips(Path inputVideo, Path outputDir, List<TimeRange> removeRanges, double durationSeconds) {
        try {
            Files.createDirectories(outputDir);
            List<TimeRange> keepRanges = buildKeepRanges(removeRanges, durationSeconds);
            if (keepRanges.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "确认剪辑片段覆盖了整段视频，无法导出");
            }
            List<Path> parts = new ArrayList<>();
            for (int i = 0; i < keepRanges.size(); i++) {
                TimeRange range = keepRanges.get(i);
                Path part = outputDir.resolve("keep-" + i + ".mp4");
                run(List.of(
                        properties.ffmpegPath(), "-y",
                        "-ss", formatSeconds(range.start()),
                        "-i", inputVideo.toString(),
                        "-t", formatSeconds(range.end() - range.start()),
                        "-c", "copy",
                        part.toString()
                ));
                parts.add(part);
            }
            Path concatList = outputDir.resolve("concat.txt");
            StringBuilder listContent = new StringBuilder();
            for (Path part : parts) {
                String path = part.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''");
                listContent.append("file '").append(path).append("'\n");
            }
            Files.writeString(concatList, listContent.toString());
            Path output = outputDir.resolve("moderated-" + System.currentTimeMillis() + ".mp4");
            run(List.of(
                    properties.ffmpegPath(), "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", concatList.toString(),
                    "-c", "copy",
                    output.toString()
            ));
            return output;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "导出视频失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
    }

    private List<TimeRange> buildKeepRanges(List<TimeRange> removeRanges, double durationSeconds) {
        List<TimeRange> sorted = removeRanges.stream()
                .map(range -> new TimeRange(Math.max(0, range.start()), Math.min(durationSeconds, range.end())))
                .filter(range -> range.end() > range.start())
                .sorted((a, b) -> Double.compare(a.start(), b.start()))
                .toList();
        List<TimeRange> keep = new ArrayList<>();
        double cursor = 0;
        for (TimeRange remove : sorted) {
            if (remove.start() > cursor + 0.05) {
                keep.add(new TimeRange(cursor, remove.start()));
            }
            cursor = Math.max(cursor, remove.end());
        }
        if (cursor < durationSeconds - 0.05) {
            keep.add(new TimeRange(cursor, durationSeconds));
        }
        return keep;
    }

    private void run(List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg 执行失败: " + output);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg 执行被中断");
        }
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    public record TimeRange(double start, double end) {
    }
}
