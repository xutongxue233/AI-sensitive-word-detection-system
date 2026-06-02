package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.asr.TranscriptionResult;
import com.ai.moderation.asr.TranscriptionSegment;
import com.ai.moderation.asr.TranscriptionWord;
import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptWord;
import com.ai.moderation.dto.TranscriptSegmentResponse;
import com.ai.moderation.dto.TranscriptWordResponse;
import com.ai.moderation.repository.TranscriptSegmentRepository;
import com.ai.moderation.repository.TranscriptWordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TranscriptService {
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptWordRepository wordRepository;
    private final TextNormalizer textNormalizer;

    public TranscriptService(
            TranscriptSegmentRepository segmentRepository,
            TranscriptWordRepository wordRepository,
            TextNormalizer textNormalizer
    ) {
        this.segmentRepository = segmentRepository;
        this.wordRepository = wordRepository;
        this.textNormalizer = textNormalizer;
    }

    @Transactional
    public void replaceTranscript(DetectionJob job, TranscriptionResult result) {
        wordRepository.deleteByJobId(job.getId());
        segmentRepository.deleteByJobId(job.getId());
        int segmentIndex = 0;
        int wordIndex = 0;
        for (TranscriptionSegment item : result.segments()) {
            TranscriptSegment segment = new TranscriptSegment();
            segment.setJobId(job.getId());
            segment.setSequenceNo(segmentIndex++);
            segment.setStartTime(item.start());
            segment.setEndTime(item.end());
            segment.setText(item.text() == null ? "" : item.text());
            TranscriptSegment savedSegment = segmentRepository.save(segment);

            List<TranscriptWord> words = new ArrayList<>();
            List<TranscriptionWord> sourceWords = item.words() == null ? List.of() : item.words();
            for (TranscriptionWord sourceWord : sourceWords) {
                String normalized = textNormalizer.normalizeForMatch(sourceWord.word());
                if (normalized.isBlank()) {
                    continue;
                }
                TranscriptWord word = new TranscriptWord();
                word.setJobId(job.getId());
                word.setSegmentId(savedSegment.getId());
                word.setSequenceNo(wordIndex++);
                word.setWord(sourceWord.word().trim());
                word.setNormalizedWord(normalized);
                word.setStartTime(sourceWord.start());
                word.setEndTime(sourceWord.end());
                words.add(word);
            }
            wordRepository.saveAll(words);
        }
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegmentResponse> listSegments(Long jobId) {
        List<TranscriptSegment> segments = segmentRepository.findByJobIdOrderBySequenceNoAsc(jobId);
        Map<Long, List<TranscriptWordResponse>> wordsBySegment = wordRepository.findByJobIdOrderBySequenceNoAsc(jobId)
                .stream()
                .collect(Collectors.groupingBy(
                        TranscriptWord::getSegmentId,
                        Collectors.mapping(TranscriptWordResponse::from, Collectors.toList())
                ));
        return segments.stream()
                .map(segment -> TranscriptSegmentResponse.from(segment, wordsBySegment.getOrDefault(segment.getId(), List.of())))
                .toList();
    }
}
