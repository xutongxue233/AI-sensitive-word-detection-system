package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.asr.TranscriptionResult;
import com.ai.moderation.asr.TranscriptionSegment;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscriptServiceTest {
    private TranscriptSegmentRepository segmentRepository;
    private TranscriptWordRepository wordRepository;
    private TranscriptService service;

    @BeforeEach
    void setUp() {
        segmentRepository = mock(TranscriptSegmentRepository.class);
        wordRepository = mock(TranscriptWordRepository.class);
        service = new TranscriptService(segmentRepository, wordRepository, new TextNormalizer());
        when(segmentRepository.save(any(TranscriptSegment.class))).thenAnswer(invocation -> {
            TranscriptSegment segment = invocation.getArgument(0);
            segment.setId(100L);
            return segment;
        });
        when(wordRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void infersWordTimestampsWhenSourceHasOnlySegmentText() {
        DetectionJob job = new DetectionJob();
        job.setId(10L);
        TranscriptionResult result = new TranscriptionResult(List.of(
                new TranscriptionSegment(1.0, 3.0, "字幕违规词", List.of())
        ));

        service.replaceTranscript(job, result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TranscriptWord>> wordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(wordRepository).saveAll(wordsCaptor.capture());
        List<TranscriptWord> words = wordsCaptor.getValue();
        assertThat(words).hasSize(5);
        assertThat(words.getFirst().getWord()).isEqualTo("字");
        assertThat(words.getFirst().getStartTime()).isEqualTo(1.0);
        assertThat(words.getLast().getEndTime()).isEqualTo(3.0);
    }
}
