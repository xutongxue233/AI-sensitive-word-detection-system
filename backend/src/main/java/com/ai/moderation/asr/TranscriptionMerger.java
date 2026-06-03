package com.ai.moderation.asr;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class TranscriptionMerger {
    private final TextNormalizer textNormalizer;

    public TranscriptionMerger(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    public TranscriptionResult merge(TranscriptionResult primary, TranscriptionResult secondary) {
        return merge(List.of(primary, secondary));
    }

    public TranscriptionResult merge(List<TranscriptionResult> results) {
        List<TranscriptionSegment> merged = new ArrayList<>();
        for (TranscriptionResult result : results) {
            for (TranscriptionSegment candidate : segments(result)) {
                if (candidate.text() == null || candidate.text().isBlank()) {
                    continue;
                }
                if (!isDuplicate(candidate, merged)) {
                    merged.add(candidate);
                }
            }
        }
        merged.sort(Comparator
                .comparingDouble(TranscriptionSegment::start)
                .thenComparingDouble(TranscriptionSegment::end)
                .thenComparing(segment -> sourceKey(segment).name()));
        return new TranscriptionResult(merged);
    }

    public TranscriptionResult mergeSameSource(TranscriptionResult primary, TranscriptionResult secondary) {
        List<TranscriptionSegment> merged = new ArrayList<>(segments(primary));
        for (TranscriptionSegment candidate : segments(secondary)) {
            if (candidate.text() == null || candidate.text().isBlank()) {
                continue;
            }
            if (!isDuplicate(candidate, merged)) {
                merged.add(candidate);
            }
        }
        merged.sort(Comparator
                .comparingDouble(TranscriptionSegment::start)
                .thenComparingDouble(TranscriptionSegment::end));
        return new TranscriptionResult(merged);
    }

    private List<TranscriptionSegment> segments(TranscriptionResult result) {
        if (result == null || result.segments() == null) {
            return List.of();
        }
        return result.segments();
    }

    private boolean isDuplicate(TranscriptionSegment candidate, List<TranscriptionSegment> existing) {
        String candidateText = normalize(candidate.text());
        if (candidateText.isBlank()) {
            return true;
        }
        for (TranscriptionSegment segment : existing) {
            if (!Objects.equals(sourceKey(candidate), sourceKey(segment))) {
                continue;
            }
            if (!hasEnoughOverlap(candidate, segment)) {
                continue;
            }
            String existingText = normalize(segment.text());
            if (existingText.isBlank()) {
                continue;
            }
            if (existingText.equals(candidateText)
                    || existingText.contains(candidateText)
                    || candidateText.contains(existingText)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEnoughOverlap(TranscriptionSegment a, TranscriptionSegment b) {
        double start = Math.max(a.start(), b.start());
        double end = Math.min(a.end(), b.end());
        double overlap = Math.max(0, end - start);
        double shorter = Math.max(0.01, Math.min(a.end() - a.start(), b.end() - b.start()));
        return overlap / shorter >= 0.5;
    }

    private String normalize(String text) {
        return textNormalizer.normalizeForMatch(text);
    }

    private com.ai.moderation.domain.TranscriptSource sourceKey(TranscriptionSegment segment) {
        return segment.source() == null
                ? com.ai.moderation.domain.TranscriptSource.AUDIO
                : segment.source();
    }
}
