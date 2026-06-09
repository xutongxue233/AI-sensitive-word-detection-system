package com.ai.moderation.dto;

/**
 * 单个系统依赖的健康状态。
 *
 * @param key       稳定键名,供前端排序/渲染使用
 * @param label     展示名称
 * @param status    OK/WARN/DOWN
 * @param message   简短说明
 * @param elapsedMs 本项检查耗时毫秒,不可测时为 null
 */
public record HealthItemResponse(
        String key,
        String label,
        String status,
        String message,
        Long elapsedMs
) {
}
