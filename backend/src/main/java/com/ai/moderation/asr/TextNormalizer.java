package com.ai.moderation.asr;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class TextNormalizer {
    public String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    public List<TextToken> tokenize(String text) {
        List<TextToken> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalizedText = Normalizer.normalize(text, Normalizer.Form.NFKC);
        int i = 0;
        while (i < normalizedText.length()) {
            char ch = normalizedText.charAt(i);
            if (!Character.isLetterOrDigit(ch)) {
                i++;
                continue;
            }
            int start = i;
            if (isCjk(ch)) {
                String raw = normalizedText.substring(i, i + 1);
                tokens.add(new TextToken(raw, normalizeForMatch(raw), start, i + 1));
                i++;
                continue;
            }
            StringBuilder builder = new StringBuilder();
            while (i < normalizedText.length()) {
                char current = normalizedText.charAt(i);
                if (!Character.isLetterOrDigit(current) || isCjk(current)) {
                    break;
                }
                builder.append(current);
                i++;
            }
            String raw = builder.toString();
            tokens.add(new TextToken(raw, normalizeForMatch(raw), start, i));
        }
        return tokens;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}

