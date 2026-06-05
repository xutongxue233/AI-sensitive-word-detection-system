package com.ai.moderation.asr;

import com.ai.moderation.domain.TranscriptSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部字幕解析器:把上传的 SRT/VTT 解析为转写管线统一的 {@link TranscriptionResult}。
 *
 * <p>在检测管线中,一旦存在外部字幕文件即优先采用本解析结果,跳过画面 OCR 腿(腿 B)。
 * 兼容 SRT(逗号毫秒分隔)与 WEBVTT(点号毫秒、含 {@code WEBVTT} 头),按时间轴行切段。
 */
@Component
public class SubtitleParser {
    /** 匹配「开始时间 --&gt; 结束时间」时间轴行,分组 1/2 分别为起止时间字符串。 */
    private static final Pattern TIME_LINE = Pattern.compile("(.+?)\\s+-->\\s+(.+)");

    private final TextNormalizer textNormalizer;

    public SubtitleParser(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    /**
     * 解析字幕文件,来源默认为 {@link TranscriptSource#SUBTITLE_FILE}。
     *
     * @param subtitlePath 字幕文件路径(SRT/VTT)
     * @return 解析得到的转写结果
     * @throws IOException 读取文件失败
     */
    public TranscriptionResult parse(Path subtitlePath) throws IOException {
        return parse(subtitlePath, TranscriptSource.SUBTITLE_FILE);
    }

    /**
     * 解析字幕文件并指定来源。逐行扫描:跳过空行/序号行/{@code WEBVTT} 头,遇时间轴行则收集其后连续文本行成段。
     *
     * @param subtitlePath 字幕文件路径(SRT/VTT)
     * @param source       标注到各段的转写来源
     * @return 解析得到的转写结果
     * @throws IOException 读取文件失败
     */
    public TranscriptionResult parse(Path subtitlePath, TranscriptSource source) throws IOException {
        List<String> lines = Files.readAllLines(subtitlePath, StandardCharsets.UTF_8);
        List<TranscriptionSegment> segments = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.equalsIgnoreCase("WEBVTT") || line.matches("^\\d+$")) {
                i++;
                continue;
            }
            Matcher matcher = TIME_LINE.matcher(line);
            if (!matcher.matches()) {
                i++;
                continue;
            }
            double start = parseTime(matcher.group(1));
            double end = parseTime(matcher.group(2));
            i++;
            StringBuilder text = new StringBuilder();
            while (i < lines.size() && !lines.get(i).trim().isEmpty()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(lines.get(i).trim());
                i++;
            }
            String segmentText = text.toString();
            segments.add(new TranscriptionSegment(start, end, segmentText, inferWords(segmentText, start, end),
                    source, null, null, null, null));
        }
        return new TranscriptionResult(segments);
    }

    /**
     * 伪造词级时间戳:外部字幕只有句级时间,按 token 数把段时长均分,为每个词推算起止时刻供命中定位。
     *
     * <p>段时长下限取 0.01 秒以防除零;末词结束时刻对齐段结束,避免累积误差导致越界。
     *
     * @param text  段文本
     * @param start 段起始时刻(秒)
     * @param end   段结束时刻(秒)
     * @return 伪造的词级时间戳列表;无 token 时为空
     */
    private List<TranscriptionWord> inferWords(String text, double start, double end) {
        List<TextToken> tokens = textNormalizer.tokenize(text);
        List<TranscriptionWord> words = new ArrayList<>();
        if (tokens.isEmpty()) {
            return words;
        }
        double duration = Math.max(0.01, end - start);
        double step = duration / tokens.size();
        for (int i = 0; i < tokens.size(); i++) {
            double wordStart = start + step * i;
            double wordEnd = i == tokens.size() - 1 ? end : start + step * (i + 1);
            words.add(new TranscriptionWord(tokens.get(i).text(), wordStart, wordEnd));
        }
        return words;
    }

    /**
     * 把字幕时间字符串解析为秒。兼容 {@code HH:MM:SS,mmm}(SRT 逗号)、{@code HH:MM:SS.mmm}(VTT 点号)、
     * 以及缺省时/分的短格式;先取首段(忽略时间后可能跟随的样式参数),并将逗号毫秒分隔统一为点号。
     *
     * @param value 时间字符串
     * @return 对应秒数
     */
    private double parseTime(String value) {
        String clean = value.trim().split("\\s+")[0].replace(',', '.');
        String[] parts = clean.split(":");
        if (parts.length == 3) {
            return Integer.parseInt(parts[0]) * 3600
                    + Integer.parseInt(parts[1]) * 60
                    + Double.parseDouble(parts[2]);
        }
        if (parts.length == 2) {
            return Integer.parseInt(parts[0]) * 60 + Double.parseDouble(parts[1]);
        }
        return Double.parseDouble(clean);
    }
}
