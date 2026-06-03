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

    // delogo 字幕修复参数:外扩比例(覆盖字幕描边/抗锯齿边缘),以及超宽字幕条的横向分块阈值与单块像素上限
    // (分块让每块 delogo 的上下边界采样更贴合该列局部背景,减轻整条插值发虚)
    private static final double DELOGO_PADDING_X = 0.012;
    private static final double DELOGO_PADDING_Y = 0.010;
    private static final int DELOGO_CHUNK_TRIGGER_PX = 700;
    private static final int DELOGO_MAX_CHUNK_PX = 350;

    // VSR 切片/拼接统一编码参数:keep 段与 VSR 处理段都重编码为此参数,才能用 concat 流复制无缝拼接
    private static final List<String> STANDARD_ENCODE = List.of(
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100"
    );

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

    /**
     * 探测首个视频流的像素宽高,供 delogo 把归一化字幕框换算为整数像素坐标。
     * delogo 不接受 iw/ih 这类相对表达式,必须传整数像素;失败返回 null,由调用方回退盒式模糊。
     */
    public int[] probeResolution(Path videoPath) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    properties.ffprobePath(),
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    videoPath.toString()
            );
            Process process = builder.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                return null;
            }
            String line = output.lines().map(String::trim).filter(value -> value.contains("x")).findFirst().orElse("");
            String[] parts = line.split("x");
            if (parts.length < 2) {
                return null;
            }
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            // delogo 区域外需留至少 1px,W/H < 3 无法容纳合法遮盖框,返回 null 触发盒式模糊回退
            if (width < 3 || height < 3) {
                return null;
            }
            return new int[]{width, height};
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
            int[] resolution = probeResolution(inputVideo);
            // 首选 delogo:对字幕区域做邻域插值修复,抹除字幕并融入背景,避免盒式模糊残留笔画轮廓的马赛克感
            String delogoFilter = resolution == null
                    ? ""
                    : buildSubtitleDelogoFilter(maskRanges, resolution[0], resolution[1]);
            // delogo 不可用(无法探测分辨率,或所有区域换算后无效)时回退盒式模糊,保证遮盖不被静默跳过
            String filter = delogoFilter.isBlank() ? buildSubtitleBlurFilter(maskRanges) : delogoFilter;
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

    /**
     * 为每个字幕遮盖区域构建 delogo 滤镜链(filter_complex)。归一化坐标按视频像素宽高换算为整数,
     * 并做硬 clamp 满足 delogo 约束(x&gt;=1,y&gt;=1,x+w&lt;=W-1,y+h&lt;=H-1);超宽字幕条横向分块。
     * 每个 delogo 带 enable='between(t,start,end)' 仅在字幕出现时段生效。全部区域无效时返回空串。
     */
    private String buildSubtitleDelogoFilter(List<SubtitleMaskRange> ranges, int width, int height) {
        List<String> parts = new ArrayList<>();
        for (SubtitleMaskRange range : ranges) {
            // 在归一化坐标上适度外扩,覆盖字幕描边/抗锯齿边缘
            double nx = range.x() - DELOGO_PADDING_X;
            double ny = range.y() - DELOGO_PADDING_Y;
            double nw = range.width() + 2 * DELOGO_PADDING_X;
            double nh = range.height() + 2 * DELOGO_PADDING_Y;
            int x = (int) Math.round(nx * width);
            int y = (int) Math.round(ny * height);
            int w = (int) Math.round(nw * width);
            int h = (int) Math.round(nh * height);
            // delogo 硬约束:区域外需留至少 1px 供采样
            x = clampInt(x, 1, width - 2);
            y = clampInt(y, 1, height - 2);
            w = clampInt(w, 1, width - 1 - x);
            h = clampInt(h, 1, height - 1 - y);
            if (w < 1 || h < 1) {
                continue;
            }
            double start = Math.max(0, range.start());
            double end = Math.max(start + 0.1, range.end());
            String enable = ":enable='between(t," + formatSeconds(start) + "," + formatSeconds(end) + ")'";
            if (w > DELOGO_CHUNK_TRIGGER_PX) {
                // 超宽字幕条横向分块,相邻块连续不留缝(delogo 主要靠上下边界采样,块间无需留列)
                int chunks = (int) Math.ceil((double) w / DELOGO_MAX_CHUNK_PX);
                int baseWidth = w / chunks;
                int cx = x;
                for (int k = 0; k < chunks; k++) {
                    int cw = k == chunks - 1 ? x + w - cx : baseWidth;
                    if (cx + cw > width - 1) {
                        cw = width - 1 - cx;
                    }
                    if (cw < 1) {
                        break;
                    }
                    parts.add("delogo=x=" + cx + ":y=" + y + ":w=" + cw + ":h=" + h + enable);
                    cx += cw;
                }
            } else {
                parts.add("delogo=x=" + x + ":y=" + y + ":w=" + w + ":h=" + h + enable);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "[0:v]" + String.join(",", parts) + "[vout]";
    }

    private static int clampInt(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** 切出 [start,end] 片段并标准化编码(便于与其他段无缝 concat)。供 VSR 按违规时间段切片用。 */
    public Path cutSegment(Path input, double start, double end, Path output) {
        try {
            Files.createDirectories(output.getParent());
            List<String> command = new ArrayList<>(List.of(
                    properties.ffmpegPath(), "-y",
                    "-ss", formatSeconds(Math.max(0, start)),
                    "-i", input.toString(),
                    "-t", formatSeconds(Math.max(0.04, end - start))
            ));
            command.addAll(STANDARD_ENCODE);
            command.add(output.toString());
            run(command);
            return output;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "切分视频片段失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
    }

    /** 把任意来源视频(如 VSR 输出)重编码为标准参数,使其能与切片无缝 concat。 */
    public Path reencodeStandard(Path input, Path output) {
        try {
            Files.createDirectories(output.getParent());
            List<String> command = new ArrayList<>(List.of(properties.ffmpegPath(), "-y", "-i", input.toString()));
            command.addAll(STANDARD_ENCODE);
            command.add(output.toString());
            run(command);
            return output;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "重编码视频片段失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
    }

    /** 按顺序拼接多个已标准化编码的片段(流复制,无缝)。 */
    public Path concatSegments(List<Path> parts, Path output) {
        try {
            Files.createDirectories(output.getParent());
            Path listFile = output.getParent().resolve("vsr-concat-" + System.currentTimeMillis() + ".txt");
            StringBuilder listContent = new StringBuilder();
            for (Path part : parts) {
                String path = part.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''");
                listContent.append("file '").append(path).append("'\n");
            }
            Files.writeString(listFile, listContent.toString());
            run(List.of(
                    properties.ffmpegPath(), "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", listFile.toString(),
                    "-c", "copy",
                    output.toString()
            ));
            return output;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "拼接视频片段失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
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
