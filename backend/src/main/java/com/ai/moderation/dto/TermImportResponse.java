package com.ai.moderation.dto;

/**
 * 违规词批量导入的结果统计。
 *
 * @param importedCount 实际写入的条数
 * @param skippedCount  因重复或校验失败而跳过的条数
 */
public record TermImportResponse(
        int importedCount,
        int skippedCount
) {
}

