package com.ai.moderation.dto;

import java.time.Instant;
import java.util.List;

/**
 * 系统健康检查聚合结果。
 *
 * @param status    整体状态:任一 DOWN 则 DOWN,否则任一 WARN 则 WARN,全部正常才 OK
 * @param checkedAt 检查时间
 * @param items     各依赖检查项
 */
public record SystemHealthResponse(
        String status,
        Instant checkedAt,
        List<HealthItemResponse> items
) {
}
