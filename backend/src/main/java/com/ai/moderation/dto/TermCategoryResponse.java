package com.ai.moderation.dto;

import com.ai.moderation.domain.TermCategory;

import java.time.Instant;

/**
 * 词库分类的响应体,由 {@link TermCategory} 实体投影而来。
 *
 * @param id          分类主键
 * @param name        分类名(全局唯一)
 * @param description 分类说明,可空
 * @param createdAt   创建时间
 */
public record TermCategoryResponse(
        Long id,
        String name,
        String description,
        Instant createdAt
) {
    /**
     * 从 {@link TermCategory} 实体投影出响应体。
     *
     * @param category 分类实体
     * @return 对应的响应体
     */
    public static TermCategoryResponse from(TermCategory category) {
        return new TermCategoryResponse(category.getId(), category.getName(), category.getDescription(), category.getCreatedAt());
    }
}

