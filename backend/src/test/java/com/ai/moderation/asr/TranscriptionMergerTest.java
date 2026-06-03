package com.ai.moderation.asr;

import com.ai.moderation.domain.TranscriptSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptionMergerTest {
    private final TranscriptionMerger merger = new TranscriptionMerger(new TextNormalizer());

    @Test
    void skipsOverlappingDuplicateOcrText() {
        TranscriptionResult audio = result(segment(1.0, 3.0, "这款面膜全网最低"));
        TranscriptionResult ocr = result(segment(1.1, 2.9, "全网最低"));

        TranscriptionResult merged = merger.merge(audio, ocr);

        assertThat(merged.segments()).hasSize(1);
        assertThat(merged.segments().getFirst().text()).isEqualTo("这款面膜全网最低");
    }

    @Test
    void keepsSubtitleTextMissingFromAudioTranscript() {
        TranscriptionResult audio = result(segment(1.0, 3.0, "这款面膜很好用"));
        TranscriptionResult ocr = result(segment(1.1, 2.9, "全网最低"));

        TranscriptionResult merged = merger.merge(audio, ocr);

        assertThat(merged.segments()).extracting(TranscriptionSegment::text)
                .containsExactly("这款面膜很好用", "全网最低");
    }

    @Test
    void keepsDuplicateTextWhenItComesFromDifferentLayers() {
        TranscriptionResult audio = result(segment(1.0, 3.0, "全网最低", TranscriptSource.AUDIO));
        TranscriptionResult subtitle = result(segment(1.1, 2.9, "全网最低", TranscriptSource.VIDEO_SUBTITLE));

        TranscriptionResult merged = merger.merge(audio, subtitle);

        assertThat(merged.segments()).hasSize(2);
        assertThat(merged.segments()).extracting(TranscriptionSegment::source)
                .containsExactly(TranscriptSource.AUDIO, TranscriptSource.VIDEO_SUBTITLE);
    }

    private TranscriptionResult result(TranscriptionSegment... segments) {
        return new TranscriptionResult(List.of(segments));
    }

    private TranscriptionSegment segment(double start, double end, String text) {
        return new TranscriptionSegment(start, end, text, List.of());
    }

    private TranscriptionSegment segment(double start, double end, String text, TranscriptSource source) {
        return new TranscriptionSegment(start, end, text, List.of(), source, null, null, null, null);
    }
}
