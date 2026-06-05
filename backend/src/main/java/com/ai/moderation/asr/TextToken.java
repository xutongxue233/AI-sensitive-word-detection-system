package com.ai.moderation.asr;

/**
 * 切词产物:由 {@link TextNormalizer#tokenize} 产出的单个 token。
 *
 * <p>除归一化文本外还保留 token 在原始字符串中的起止偏移,使得规则命中后能把匹配位置回填到原文,
 * 进而结合词级时间戳定位到时间轴。
 *
 * @param text        原文 token(NFKC 归一化后的原始片段,未去符号、保留大小写)
 * @param normalized  归一化形(折叠全半角 + 小写 + 去非字母数字),供规则匹配比对
 * @param sourceStart token 在源串中的起始字符偏移(含)
 * @param sourceEnd   token 在源串中的结束字符偏移(不含),用于把命中映射回原文/时间轴
 */
public record TextToken(
        String text,
        String normalized,
        int sourceStart,
        int sourceEnd
) {
}

