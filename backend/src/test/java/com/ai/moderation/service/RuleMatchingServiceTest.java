package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.domain.*;
import com.ai.moderation.repository.TermHitRepository;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import com.ai.moderation.repository.ViolationTermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RuleMatchingServiceTest {
    private ViolationTermRepository termRepository;
    private TranscriptSegmentRepository segmentRepository;
    private TranscriptWordRepository wordRepository;
    private TermHitRepository hitRepository;
    private RuleMatchingService matchingService;

    @BeforeEach
    void setUp() {
        termRepository = mock(ViolationTermRepository.class);
        segmentRepository = mock(TranscriptSegmentRepository.class);
        wordRepository = mock(TranscriptWordRepository.class);
        hitRepository = mock(TermHitRepository.class);
        TextNormalizer textNormalizer = new TextNormalizer();
        matchingService = new RuleMatchingService(
                termRepository,
                segmentRepository,
                wordRepository,
                hitRepository,
                textNormalizer,
                new SegmentTimeLocator(textNormalizer)
        );
        when(hitRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void mapsMatchedTermToWordLevelTimestamp() {
        DetectionJob job = job();
        TranscriptSegment segment = segment("这里有违规词", 0, 5);
        ViolationTerm term = term("违规词", MatchType.EXACT);

        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(job.getId())).thenReturn(List.of(segment));
        when(wordRepository.findBySegmentIdOrderBySequenceNoAsc(segment.getId())).thenReturn(List.of(
                word(0, "这", 0.0, 0.5),
                word(1, "里", 0.5, 1.0),
                word(2, "有", 1.0, 1.5),
                word(3, "违", 2.0, 2.4),
                word(4, "规", 2.4, 2.8),
                word(5, "词", 2.8, 3.2)
        ));

        List<TermHit> hits = matchingService.matchJob(job);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().getStartTime()).isEqualTo(2.0);
        assertThat(hits.getFirst().getEndTime()).isEqualTo(3.2);
        assertThat(hits.getFirst().getMatchedText()).isEqualTo("违规词");
    }

    @Test
    void semanticPriceTermRecallsPriceLikeExpressions() {
        DetectionJob job = job();
        TranscriptSegment segment = segment("今天下单只要1块钱，第二件29.9包邮", 0, 8);
        ViolationTerm term = term("价格", MatchType.SEMANTIC);

        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(job.getId())).thenReturn(List.of(segment));
        when(wordRepository.findBySegmentIdOrderBySequenceNoAsc(segment.getId())).thenReturn(List.of());

        List<TermHit> hits = matchingService.matchJob(job);

        assertThat(hits).extracting(TermHit::getMatchedText)
                .contains("1块钱", "29.9");
    }

    @Test
    void semanticPriceTermDoesNotRecallPlainNumbersWithoutPriceContext() {
        DetectionJob job = job();
        TranscriptSegment segment = segment("今天第29.9秒出现字幕", 0, 8);
        ViolationTerm term = term("价格", MatchType.SEMANTIC);

        when(termRepository.findByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(List.of(term));
        when(segmentRepository.findByJobIdOrderBySequenceNoAsc(job.getId())).thenReturn(List.of(segment));
        when(wordRepository.findBySegmentIdOrderBySequenceNoAsc(segment.getId())).thenReturn(List.of());

        List<TermHit> hits = matchingService.matchJob(job);

        assertThat(hits).isEmpty();
    }

    private DetectionJob job() {
        DetectionJob job = new DetectionJob();
        job.setId(10L);
        job.setVideoId(20L);
        return job;
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

    private TranscriptWord word(int index, String value, double start, double end) {
        TranscriptWord word = new TranscriptWord();
        word.setId((long) index + 1);
        word.setJobId(10L);
        word.setSegmentId(30L);
        word.setSequenceNo(index);
        word.setWord(value);
        word.setNormalizedWord(value);
        word.setStartTime(start);
        word.setEndTime(end);
        return word;
    }

    private ViolationTerm term(String value, MatchType matchType) {
        ViolationTerm term = new ViolationTerm();
        term.setId(40L);
        term.setTerm(value);
        term.setCategory(value);
        term.setSeverity(Severity.HIGH);
        term.setMatchType(matchType);
        return term;
    }
}
