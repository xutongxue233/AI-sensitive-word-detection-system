package com.ai.moderation.asr;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文本归一化与切词基础工具:被规则匹配、转写合并去重等多模块依赖。
 *
 * <p>归一化统一全半角与大小写差异,切词区分 CJK 与拉丁文以适配中英混排的敏感词召回。
 */
@Component
public class TextNormalizer {
    /**
     * 归一化为可比对形:NFKC 折叠全/半角等兼容字符 → 小写 → 仅保留字母数字(去标点/空白/符号)。
     *
     * <p>用于规则命中比对,使「ＡＢＣ」「abc」「a b c」折叠为同一形,消除来源排版差异。
     *
     * @param text 原始文本,允许为 null
     * @return 归一化后的纯字母数字串;入参为 null 时返回空串
     */
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

    /**
     * 切词:CJK 逐字成 token,拉丁连续字母数字聚合成词。
     *
     * <p>每个 {@link TextToken} 保留其在(NFKC 归一化后)源串中的起止偏移,供命中回填原文与定位时间轴。
     * 非字母数字字符视为分隔符跳过。
     *
     * @param text 待切词文本,允许为 null 或空白
     * @return token 列表,按源串顺序排列;无内容时返回空列表
     */
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

    /**
     * 判定字符是否属于 CJK 文字体系(汉字 / 日文假名 / 韩文谚文)。
     *
     * <p>此类文字无空格分词,故在 {@link #tokenize} 中按单字切分;拉丁等其余文字则聚合成词。
     *
     * @param ch 待判定字符
     * @return 属于上述文字体系返回 true
     */
    private boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}

