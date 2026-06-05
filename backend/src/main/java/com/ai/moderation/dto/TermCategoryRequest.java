package com.ai.moderation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 词库分类的新建/更新请求。
 *
 * <p>分类用于给 {@link com.ai.moderation.domain.ViolationTerm 违规词} 归类管理,前端按分类筛选/统计命中。
 *
 * @param name        分类名,必填且全局唯一(同名分类不允许重复创建)
 * @param description 分类说明,可空,仅用于人读备注
 */
public record TermCategoryRequest(
        @NotBlank String name,
        String description
) {
}

