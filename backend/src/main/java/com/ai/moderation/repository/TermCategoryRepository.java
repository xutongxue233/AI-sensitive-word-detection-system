package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.TermCategory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Optional;

/**
 * {@code term_categories} 表读写,对应实体 {@link TermCategory}。
 *
 * <p>违规词分类字典,供 {@link com.ai.moderation.domain.ViolationTerm} 归类与前端筛选使用。</p>
 */
public interface TermCategoryRepository extends BaseCrudMapper<TermCategory> {

    /**
     * 按分类名称精确查询。
     *
     * <p>限定 {@code LIMIT 1}:分类名称应唯一,取首条即可。</p>
     *
     * @param name 分类名称
     * @return 对应分类,无则为空
     */
    default Optional<TermCategory> findByName(String name) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(TermCategory.class)
                .eq(TermCategory::getName, name)
                .last("LIMIT 1")));
    }
}
