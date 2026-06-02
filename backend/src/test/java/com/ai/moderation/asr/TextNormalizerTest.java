package com.ai.moderation.asr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {
    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void normalizesMixedChineseAndEnglishTextForMatching() {
        assertThat(normalizer.normalizeForMatch("  违-规 Word！ＡＢＣ 123 "))
                .isEqualTo("违规wordabc123");
    }

    @Test
    void tokenizesChineseCharactersAndLatinWords() {
        assertThat(normalizer.tokenize("违规 Word123"))
                .extracting(TextToken::normalized)
                .containsExactly("违", "规", "word123");
    }
}

