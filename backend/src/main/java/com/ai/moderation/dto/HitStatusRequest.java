package com.ai.moderation.dto;

import com.ai.moderation.domain.ReviewStatus;

/**
 * 更新命中复核状态的请求体。
 *
 * @param status 目标复核状态
 */
public record HitStatusRequest(ReviewStatus status) {
}
