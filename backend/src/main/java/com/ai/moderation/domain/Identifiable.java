package com.ai.moderation.domain;

/**
 * 统一标识"带 Long 自增主键"的实体。
 *
 * <p>供 {@link com.ai.moderation.common.mybatis.BaseCrudMapper} 按主键做泛化 CRUD,
 * 使各实体无需重复定义主键存取契约。</p>
 */
public interface Identifiable {
    Long getId();

    void setId(Long id);
}

