package com.ai.moderation.repository;

import com.ai.moderation.domain.TermCategory;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Optional;

public interface TermCategoryRepository extends BaseCrudMapper<TermCategory> {
    default Optional<TermCategory> findByName(String name) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(TermCategory.class)
                .eq(TermCategory::getName, name)
                .last("LIMIT 1")));
    }
}
