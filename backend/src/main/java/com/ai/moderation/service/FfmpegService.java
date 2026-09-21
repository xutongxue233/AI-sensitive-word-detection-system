package com.ai.moderation.service;

import com.ai.moderation.common.ApiException;
import com.ai.moderation.config.FfmpegProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * FFmpeg/ffprobe 命令行封装:检测管线与导出阶段对外部 FFmpeg 进程的唯一入口。
 * 职责:
 * - {@link #extractAudio} 抽取 16k 单声道 wav,供 Whisper ASR 转写;
 * - {@link #probeDuration}/{@link #probeResolution} 用 ffprobe 探测时长与首个视频流分辨率;
 * - {@link #exportWithoutClips} 删除命中时间片段(切出保留段再 concat 拼接),用于音频命中导出;
 * - {@link #exportWithSubtitleBlur} 用 delogo 邻域插值去字幕(失败回退盒式模糊),用于画面硬字幕命中导出。
 * DELOGO_* 常量为 delogo 修复参数(外扩与分块,见各常量注释)。
 * 导出重编码支持硬件编码器(AMF/QSV/NVENC,核显即可,由 app.ffmpeg.hw-encoder 控制):首次编码前
 * 用微型试编码懒探测一次并缓存;真实导出若仍失败则降级 libx264 重跑,硬件路径永不阻断导出。
 * 滤镜(delogo/gblur 等)仍在 CPU 执行,硬件只加速编码。
 * FFmpeg 可执行路径由 {@link FfmpegProperties} 提供;任何 FFmpeg 调用失败统一抛 {@link ApiException}。
 */
@Service
public class FfmpegService {
    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private final FfmpegProperties properties;

    // delogo 字幕修复参数:外扩比例(覆盖字幕描边/抗锯齿边缘),以及超宽字幕条的横向分块阈值与单块像素上限
    // (分块让每块 delogo 的上下边界采样更贴合该列局部背景,减轻整条插值发虚)
    private static final double DELOGO_PADDING_X = 0.035;
    private static final double DELOGO_PADDING_Y = 0.016;
    private static final int DELOGO_CHUNK_TRIGGER_PX = 700;
    private static final int DELOGO_MAX_CHUNK_PX = 350;

    /** CPU 兜底编码参数:硬件编码器不可用或对真实素材执行失败时使用。 */
    private static final List<String> CPU_ENCODE_ARGS = List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18");

    /**
     * 硬件 H.264 编码器候选,按部署目标优先级排列:AMF(AMD 核显/独显)→ QSV(Intel 核显)→ NVENC(NVIDIA)。
     * 硬件编码器不支持 x264 的 -crf,各自改用等价的恒质量码控,档位对齐 libx264 crf 18;
     * 探测时即用候选的完整参数做试编码,参数不被当前驱动/FFmpeg 构建支持会直接探测失败并跳过该候选。
     */
    private static final List<EncoderCandidate> HW_ENCODE_CANDIDATES = List.of(
            new EncoderCandidate("amf", List.of("-c:v", "h264_amf", "-quality", "quality", "-rc", "cqp", "-qp_i", "18", "-qp_p", "20")),
            new EncoderCandidate("qsv", List.of("-c:v", "h264_qsv", "-preset", "veryfast", "-global_quality", "20")),
            new EncoderCandidate("nvenc", List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "19"))
    );

    /** 懒探测后的编码参数缓存;运行期硬件编码失败时会被降级覆写为 {@link #CPU_ENCODE_ARGS}。 */
    private volatile List<String> resolvedEncodeArgs;

    public FfmpegService(FfmpegProperties properties) {
        this.properties = properties;
    }

    /**
     * 抽取视频音轨为 16kHz 单声道 wav(Whisper 要求的输入规格),写到 outputDir/audio.wav。
     * 这是音频转写腿的第一步,失败即视为任务失败(不降级)。
     *
     * @param videoPath 源视频路径
     * @param outputDir 输出目录(不存在则创建)
     * @return 生成的 audio.wav 路径
     */
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

    /**
     * 用 ffprobe 探测视频时长(秒)。探测失败或输出非法时返回 null,由调用方决定如何兜底(如另行估算)。
     *
     * @param videoPath 源视频路径
     * @return 时长秒数;不可用时为 null
     */
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

    /**
     * 删除命中片段导出的便捷重载:默认非精确(precise=false,流复制、边界吸附关键帧、倾向少切)。
     * 详见 {@link #exportWithoutClips(Path, Path, List, double, boolean)}。
     *
     * @param removeRanges    需删除的时间片段
     * @param durationSeconds 视频总时长(用于反推保留片段)
     * @return 导出的成品视频路径
     */
    public Path exportWithoutClips(Path inputVideo, Path outputDir, List<TimeRange> removeRanges, double durationSeconds) {
        return exportWithoutClips(inputVideo, outputDir, removeRanges, durationSeconds, false);
    }

    /**
     * 画面硬字幕命中的导出:对各字幕遮盖区域去字幕后输出新视频。
     * 首选 delogo 邻域插值修复(融入背景,避免盒式模糊残留笔画的马赛克感);
     * 当分辨率探测失败或所有区域换算后无效时,回退盒式模糊,保证遮盖不被静默跳过。
     * maskRanges 为空时直接返回原视频(无需处理)。
     *
     * @param maskRanges 归一化字幕遮盖框 + 生效时段({@link SubtitleMaskRange})
     * @return 处理后的成品视频路径;无遮盖区域时返回入参原视频
     */
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
            runWithVideoEncoder(encodeArgs -> {
                List<String> command = new ArrayList<>(List.of(
                        properties.ffmpegPath(), "-y",
                        "-i", inputVideo.toString(),
                        "-filter_complex", filter,
                        "-map", "[vout]",
                        "-map", "0:a?"));
                command.addAll(encodeArgs);
                command.addAll(List.of("-c:a", "copy", output.toString()));
                return command;
            });
            return output;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "导出字幕遮盖视频失败: 找不到或无法执行 FFmpeg，请检查 app.ffmpeg.ffmpeg-path=" + properties.ffmpegPath());
        }
    }

    /**
     * 音频命中的导出:把 removeRanges 反推成保留片段,逐段切出后用 concat 拼接成新视频。
     * 删除片段覆盖整段视频(无保留片段)时报 400。
     *
     * @param removeRanges    需删除的时间片段
     * @param durationSeconds 视频总时长(用于反推保留片段)
     * @param precise         true 时对保留片段重编码以实现帧级精确切割,避免流复制按关键帧吸附;
     *                        false 时使用流复制(快速,边界吸附到关键帧,倾向少切)。
     * @return 导出的成品视频路径
     */
    public Path exportWithoutClips(Path inputVideo, Path outputDir, List<TimeRange> removeRanges, double durationSeconds, boolean precise) {
        try {
            Files.createDirectories(outputDir);
            List<TimeRange> keepRanges = buildKeepRanges(removeRanges, durationSeconds);
            if (keepRanges.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "确认剪辑片段覆盖了整段视频，无法导出");
            }
            List<Path> parts = new ArrayList<>();
            if (precise) {
                List<String> encodeArgs = videoEncodeArgs();
                try {
                    parts = encodeKeepRanges(inputVideo, outputDir, keepRanges, encodeArgs);
                } catch (ApiException ex) {
                    if (CPU_ENCODE_ARGS.equals(encodeArgs)) {
                        throw ex;
                    }
                    log.warn("精确导出中的硬件编码失败,重新用 libx264 生成全部片段: {}", ex.getMessage());
                    resolvedEncodeArgs = CPU_ENCODE_ARGS;
                    deleteKeepParts(outputDir, keepRanges.size());
                    parts = encodeKeepRanges(inputVideo, outputDir, keepRanges, CPU_ENCODE_ARGS);
                }
            } else {
                for (int i = 0; i < keepRanges.size(); i++) {
                    TimeRange range = keepRanges.get(i);
                    Path part = outputDir.resolve("keep-" + i + ".mp4");
                    List<String> head = List.of(
                            properties.ffmpegPath(), "-y",
                            "-ss", formatSeconds(range.start()),
                            "-i", inputVideo.toString(),
                            "-t", formatSeconds(range.end() - range.start())
                    );
                    List<String> command = new ArrayList<>(head);
                    command.addAll(List.of("-c", "copy", part.toString()));
                    run(command);
                    parts.add(part);
                }
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

    /** 用同一套视频编码参数生成全部精确裁剪片段,保证 concat -c copy 的流参数一致。 */
    private List<Path> encodeKeepRanges(
            Path inputVideo,
            Path outputDir,
            List<TimeRange> keepRanges,
            List<String> encodeArgs
    ) throws IOException {
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
            command.addAll(encodeArgs);
            command.addAll(List.of("-c:a", "aac", part.toString()));
            run(command);
            parts.add(part);
        }
        return parts;
    }

    private void deleteKeepParts(Path outputDir, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            Files.deleteIfExists(outputDir.resolve("keep-" + i + ".mp4"));
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
                    .append(",boxblur=10:3,format=yuv420p[")
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
     * 为每个字幕遮盖区域构建"delogo 去字幕 + 高斯模糊 + 边缘羽化"的柔滑滤镜链(filter_complex):
     * 先 delogo 邻域插值抹除字幕,再对该区域高斯模糊柔化插值痕迹,并用 alpha 渐变羽化边缘后叠回,
     * 消除矩形硬边与拉伸接缝。归一化坐标按像素换算并 clamp;每段带 enable 仅在字幕出现时段生效。
     */
    private String buildSubtitleDelogoFilter(List<SubtitleMaskRange> ranges, int width, int height) {
        List<int[]> boxes = new ArrayList<>();
        List<double[]> spans = new ArrayList<>();
        for (SubtitleMaskRange range : ranges) {
            // 归一化坐标外扩一点(覆盖描边/抗锯齿)后换算为像素,并 clamp 满足 delogo 约束(区域外留 >=1px)
            double nx = range.x() - DELOGO_PADDING_X;
            double ny = range.y() - DELOGO_PADDING_Y;
            double nw = range.width() + 2 * DELOGO_PADDING_X;
            double nh = range.height() + 2 * DELOGO_PADDING_Y;
            int x = clampInt((int) Math.round(nx * width), 1, width - 2);
            int y = clampInt((int) Math.round(ny * height), 1, height - 2);
            int w = clampInt((int) Math.round(nw * width), 1, width - 1 - x);
            int h = clampInt((int) Math.round(nh * height), 1, height - 1 - y);
            if (w < 4 || h < 4) {
                continue;
            }
            boxes.add(new int[]{x, y, w, h});
            spans.add(new double[]{Math.max(0, range.start()), Math.max(range.start() + 0.1, range.end())});
        }
        if (boxes.isEmpty()) {
            return "";
        }
        StringBuilder f = new StringBuilder();
        // 1) 逐区域 delogo 抹除字幕(仅该字幕出现时段生效)
        f.append("[0:v]");
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            double[] s = spans.get(i);
            if (i > 0) {
                f.append(',');
            }
            f.append("delogo=x=").append(b[0]).append(":y=").append(b[1])
                    .append(":w=").append(b[2]).append(":h=").append(b[3])
                    .append(":enable='between(t,").append(formatSeconds(s[0])).append(',').append(formatSeconds(s[1])).append(")'");
        }
        f.append("[dl];");
        // 2) 分出 base 与每个区域,各区域高斯模糊 + 边缘羽化(alpha 距边 feather 像素内 0->1 渐变)
        f.append("[dl]split=").append(boxes.size() + 1).append("[base]");
        for (int i = 0; i < boxes.size(); i++) {
            f.append("[r").append(i).append(']');
        }
        f.append(';');
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            int feather = Math.max(8, Math.min(properties.subtitleFeatherMax(), Math.min(b[2], b[3]) * 2 / 5));
            f.append("[r").append(i).append("]crop=").append(b[2]).append(':').append(b[3])
                    .append(':').append(b[0]).append(':').append(b[1])
                    .append(",gblur=sigma=").append(formatRatio(properties.subtitleBlurSigma()))
                    .append(",format=yuva420p,geq=lum='lum(X\\,Y)':cb='cb(X\\,Y)':cr='cr(X\\,Y)':a='clip(min(min(X\\,")
                    .append(b[2] - 1).append("-X)\\,min(Y\\,").append(b[3] - 1).append("-Y))/").append(feather)
                    .append("\\,0\\,1)*255'[s").append(i).append("];");
        }
        // 3) 羽化后的柔化块按时段叠回,边缘自然过渡
        String cur = "base";
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            double[] s = spans.get(i);
            String next = i == boxes.size() - 1 ? "vout" : "m" + i;
            f.append('[').append(cur).append("][s").append(i).append("]overlay=x=").append(b[0]).append(":y=").append(b[1])
                    .append(":enable='between(t,").append(formatSeconds(s[0])).append(',').append(formatSeconds(s[1])).append(")'[")
                    .append(next).append(']');
            if (i < boxes.size() - 1) {
                f.append(';');
            }
            cur = next;
        }
        return f.toString();
    }

    private static int clampInt(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 用解析出的视频编码参数执行一次导出编码。硬件编码器虽通过了试编码探测,仍可能对真实素材失败
     * (驱动版本、异常分辨率、显存不足等):此时降级缓存为 libx264 并立即用 CPU 参数重跑本条命令,
     * 保证导出结果不因硬件路径而中断;后续导出直接走 CPU,不再反复撞同一错误。
     *
     * @param commandBuilder 接收编码参数(形如 -c:v xxx ...)、返回完整 FFmpeg 命令的构造函数
     */
    private void runWithVideoEncoder(UnaryOperator<List<String>> commandBuilder) throws IOException {
        List<String> encodeArgs = videoEncodeArgs();
        try {
            run(commandBuilder.apply(encodeArgs));
        } catch (ApiException ex) {
            if (CPU_ENCODE_ARGS.equals(encodeArgs)) {
                throw ex;
            }
            log.warn("硬件编码执行失败,本次及后续导出回退 libx264: {}", ex.getMessage());
            resolvedEncodeArgs = CPU_ENCODE_ARGS;
            run(commandBuilder.apply(CPU_ENCODE_ARGS));
        }
    }

    /** 返回当前应使用的视频编码参数;首次调用时做一次硬件编码器探测并缓存(双检锁防并发重复探测)。 */
    private List<String> videoEncodeArgs() {
        List<String> args = resolvedEncodeArgs;
        if (args == null) {
            synchronized (this) {
                if (resolvedEncodeArgs == null) {
                    resolvedEncodeArgs = resolveEncodeArgs();
                }
                args = resolvedEncodeArgs;
            }
        }
        return args;
    }

    /**
     * 按 app.ffmpeg.hw-encoder 解析编码参数:off 直接用 CPU;auto 按候选顺序逐个试编码取第一个可用;
     * 指定 amf/qsv/nvenc 时只试对应候选,不可用同样回退 CPU(只影响速度,不影响导出成败)。
     */
    private List<String> resolveEncodeArgs() {
        String mode = properties.hwEncoder();
        if ("off".equals(mode) || "none".equals(mode) || "cpu".equals(mode)) {
            return CPU_ENCODE_ARGS;
        }
        for (EncoderCandidate candidate : HW_ENCODE_CANDIDATES) {
            if (!"auto".equals(mode) && !candidate.name().equals(mode)) {
                continue;
            }
            if (probeEncoder(candidate.args())) {
                log.info("导出视频启用硬件编码器: {}", candidate.args().get(1));
                return candidate.args();
            }
        }
        log.info("未探测到可用的硬件编码器(hw-encoder={}),导出使用 CPU libx264", mode);
        return CPU_ENCODE_ARGS;
    }

    /** 对候选编码参数做一次微型试编码(lavfi 黑帧 3 帧、null 输出):驱动/构建不支持即失败。 */
    private boolean probeEncoder(List<String> encodeArgs) {
        List<String> command = new ArrayList<>(List.of(
                properties.ffmpegPath(), "-hide_banner", "-v", "error",
                "-f", "lavfi", "-i", "color=c=black:s=320x240:r=10",
                "-frames:v", "3"));
        command.addAll(encodeArgs);
        command.addAll(List.of("-f", "null", "-"));
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 硬件编码器候选:名称(与 hw-encoder 配置值对应)+ 该编码器的完整编码参数。 */
    private record EncoderCandidate(String name, List<String> args) {
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

    /**
     * 时间区间(秒)。用作删除/保留片段的表示,贯穿 {@link #exportWithoutClips} 与 {@link #buildKeepRanges}。
     *
     * @param start 起始秒
     * @param end   结束秒
     */
    public record TimeRange(double start, double end) {
    }

    /**
     * 字幕遮盖区:ffmpeg 去字幕滤镜的入参,坐标为相对画面的归一化值(0~1),并带生效时段。
     * 注意:本类是 {@link FfmpegService} 私有内嵌 record,专供构建 ffmpeg 滤镜表达式;
     * 与 service/support 包下的同名类 SubtitleMaskRange 用途不同(那个是业务层传递的遮盖数据载体),
     * 二者刻意不合并、不互相引用。
     *
     * @param start  字幕出现起始秒
     * @param end    字幕出现结束秒
     * @param x      遮盖框左上角归一化横坐标(0~1)
     * @param y      遮盖框左上角归一化纵坐标(0~1)
     * @param width  遮盖框归一化宽度(0~1)
     * @param height 遮盖框归一化高度(0~1)
     */
    public record SubtitleMaskRange(double start, double end, double x, double y, double width, double height) {
        /**
         * 把遮盖框四周外扩一点(覆盖字幕描边/抗锯齿边缘)并 clamp 到合法范围,同时保证最小尺寸与最短时段,
         * 供盒式模糊滤镜使用。
         */
        SubtitleMaskRange expanded() {
            double paddingX = 0.04;
            double paddingY = 0.022;
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
