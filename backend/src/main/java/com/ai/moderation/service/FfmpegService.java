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
        return exportWithoutClips(inputVideo, outputDir, removeRanges, durationSeconds, false);
    }

    public Path exportWithSubtitleBlur(Path inputVideo, Path outputDir, List<SubtitleMaskRange> maskRanges) {
        if (maskRanges == null || maskRanges.isEmpty()) {
            return inputVideo;
        }
        try {
            Files.createDirectories(outputDir);
            Path output = outputDir.resolve("subtitle-moderated-" + System.currentTimeMillis() + ".mp4");
            String filter = buildSubtitleBlurFilter(maskRanges);
            run(List.of(
                    properties.ffmpegPath(), "-y",
                    "-i", inputVideo.toString(),
                    "-filter_complex", filter,
                    "-map", "[vout]",
                    "-map", "0:a?",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                    "-c:a", "copy",
                    output.toString()
            ));
            return output;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "导出字幕遮盖视频失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
    }

    /**
     * @param precise true 时对保留片段重编码以实现帧级精确切割,避免流复制按关键帧吸附;
     *                false 时使用流复制(快速,边界吸附到关键帧,倾向少切)。
     */
    public Path exportWithoutClips(Path inputVideo, Path outputDir, List<TimeRange> removeRanges, double durationSeconds, boolean precise) {
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
                List<String> command = new ArrayList<>(List.of(
                        properties.ffmpegPath(), "-y",
                        "-ss", formatSeconds(range.start()),
                        "-i", inputVideo.toString(),
                        "-t", formatSeconds(range.end() - range.start())
                ));
                if (precise) {
                    command.addAll(List.of(
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                            "-c:a", "aac"
                    ));
                } else {
                    command.addAll(List.of("-c", "copy"));
                }
                command.add(part.toString());
                run(command);
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

    private String buildSubtitleBlurFilter(List<SubtitleMaskRange> ranges) {
        StringBuilder filter = new StringBuilder();
        String current = "0:v";
        for (int i = 0; i < ranges.size(); i++) {
            SubtitleMaskRange range = ranges.get(i).expanded();
            String base = "base" + i;
            String blur = "blur" + i;
            String patch = "patch" + i;
            String next = i == ranges.size() - 1 ? "vout" : "v" + i;
            filter.append('[').append(current).append("]split=2[")
                    .append(base).append("][").append(blur).append("];");
            filter.append('[').append(blur).append("]crop=w=iw*")
                    .append(formatRatio(range.width()))
                    .append(":h=ih*").append(formatRatio(range.height()))
                    .append(":x=iw*").append(formatRatio(range.x()))
                    .append(":y=ih*").append(formatRatio(range.y()))
                    .append(",boxblur=14:4,format=yuv420p[")
                    .append(patch).append("];");
            filter.append('[').append(base).append("][").append(patch)
                    .append("]overlay=x=main_w*").append(formatRatio(range.x()))
                    .append(":y=main_h*").append(formatRatio(range.y()))
                    .append(":enable='between(t,")
                    .append(formatSeconds(range.start()))
                    .append(',')
                    .append(formatSeconds(range.end()))
                    .append(")'[")
                    .append(next).append(']');
            if (i < ranges.size() - 1) {
                filter.append(';');
                current = next;
            }
        }
        return filter.toString();
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

    private String formatRatio(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    public record TimeRange(double start, double end) {
    }

    public record SubtitleMaskRange(double start, double end, double x, double y, double width, double height) {
        SubtitleMaskRange expanded() {
            double paddingX = 0.025;
            double paddingY = 0.018;
            double nx = clamp(x - paddingX, 0, 0.98);
            double ny = clamp(y - paddingY, 0, 0.98);
            double right = clamp(x + width + paddingX, 0.02, 1);
            double bottom = clamp(y + height + paddingY, 0.02, 1);
            double nw = Math.max(0.02, right - nx);
            double nh = Math.max(0.02, bottom - ny);
            return new SubtitleMaskRange(Math.max(0, start), Math.max(start + 0.1, end), nx, ny, nw, nh);
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
