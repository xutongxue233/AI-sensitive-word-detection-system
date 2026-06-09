package com.ai.moderation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量操作的通用 id 列表请求。
 */
public record BatchIdsRequest(
        @NotEmpty List<Long> ids
) {
}
