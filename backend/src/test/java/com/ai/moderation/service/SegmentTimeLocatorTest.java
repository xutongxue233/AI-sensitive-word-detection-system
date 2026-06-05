package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.service.support.SegmentTimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentTimeLocatorTest {
    private SegmentTimeLocator locator;

    @BeforeEach
    void setUp() {
        locator = new SegmentTimeLocator(new TextNormalizer());
    }

    @Test
    void locatesSubstringByWordTimestamps() {
        TranscriptSegment segment = segment("这里有违规词", 0, 5);
        List<TranscriptWord> words = List.of(
                word("这", 0.0, 0.5),
                word("里", 0.5, 1.0),
                word("有", 1.0, 1.5),
                word("违", 2.0, 2.4),
                word("规", 2.4, 2.8),
                word("词", 2.8, 3.2)
        );

        SegmentTimeRange range = locator.locate(segment, words, "违规词");

        assertThat(range.start()).isEqualTo(2.0);
        assertThat(range.end()).isEqualTo(3.2);
    }

    @Test
    void fallsBackToSegmentRangeWhenSubstringMissing() {
        TranscriptSegment segment = segment("这里有违规词", 1.5, 6.0);
        List<TranscriptWord> words = List.of(
                word("这", 1.5, 2.0),
                word("里", 2.0, 2.5)
        );

        SegmentTimeRange range = locator.locate(segment, words, "完全不存在的词");

        assertThat(range.start()).isEqualTo(1.5);
        assertThat(range.end()).isEqualTo(6.0);
    }

    @Test
    void buildsContextFromNeighbouringSegments() {
        List<TranscriptSegment> segments = List.of(
                segment("第一段", 0, 2),
                segment("第二段", 2, 4),
                segment("第三段", 4, 6)
        );

        String context = locator.buildContext(segments, 1);

        assertThat(context).contains("第一段").contains("第二段").contains("第三段");
        assertThat(context).contains("[00:00]").contains("[00:02]").contains("[00:04]");
    }

    private TranscriptSegment segment(String text, double startTime, double endTime) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(30L);
        segment.setJobId(10L);
        segment.setSequenceNo(0);
        segment.setStartTime(startTime);
        segment.setEndTime(endTime);
        segment.setText(text);
        return segment;
    }

    private TranscriptWord word(String value, double start, double end) {
        TranscriptWord word = new TranscriptWord();
        word.setSegmentId(30L);
        word.setWord(value);
        word.setNormalizedWord(value);
        word.setStartTime(start);
        word.setEndTime(end);
        return word;
    }
}
