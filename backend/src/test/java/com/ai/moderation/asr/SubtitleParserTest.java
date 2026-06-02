package com.ai.moderation.asr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleParserTest {
    private final SubtitleParser parser = new SubtitleParser(new TextNormalizer());

    @Test
    void parsesSrtSegmentsAndInfersWordTimestamps(@TempDir Path tempDir) throws Exception {
        Path subtitle = tempDir.resolve("sample.srt");
        Files.writeString(subtitle, """
                1
                00:00:01,000 --> 00:00:03,000
                这里有违规词

                2
                00:00:04,000 --> 00:00:05,500
                safe word
                """);

        TranscriptionResult result = parser.parse(subtitle);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().getFirst().start()).isEqualTo(1.0);
        assertThat(result.segments().getFirst().end()).isEqualTo(3.0);
        assertThat(result.segments().getFirst().words()).isNotEmpty();
        assertThat(result.segments().getFirst().words().getFirst().start()).isEqualTo(1.0);
    }
}

