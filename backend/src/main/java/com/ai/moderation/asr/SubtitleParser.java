package com.ai.moderation.asr;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SubtitleParser {
    private static final Pattern TIME_LINE = Pattern.compile("(.+?)\\s+-->\\s+(.+)");

    private final TextNormalizer textNormalizer;

    public SubtitleParser(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    public TranscriptionResult parse(Path subtitlePath) throws IOException {
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
            segments.add(new TranscriptionSegment(start, end, segmentText, inferWords(segmentText, start, end)));
        }
        return new TranscriptionResult(segments);
    }

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

