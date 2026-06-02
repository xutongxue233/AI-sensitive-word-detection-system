package com.ai.moderation.repository;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ViolationTerm;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface ViolationTermRepository extends BaseCrudMapper<ViolationTerm> {
    default List<ViolationTerm> findByEnabledTrueOrderByUpdatedAtDesc() {
        return selectList(Wrappers.lambdaQuery(ViolationTerm.class)
                .eq(ViolationTerm::isEnabled, true)
                .orderByDesc(ViolationTerm::getUpdatedAt));
    }

    default List<ViolationTerm> findByTermContainingIgnoreCaseOrCategoryContainingIgnoreCaseOrderByUpdatedAtDesc(String term, String category) {
        String keyword = term == null ? "" : term;
        return selectList(Wrappers.lambdaQuery(ViolationTerm.class)
                .like(ViolationTerm::getTerm, keyword)
                .or()
                .like(ViolationTerm::getCategory, category == null ? "" : category)
                .orderByDesc(ViolationTerm::getUpdatedAt));
    }

    default boolean existsByTermIgnoreCaseAndMatchType(String term, MatchType matchType) {
        return selectCount(Wrappers.lambdaQuery(ViolationTerm.class)
                .eq(ViolationTerm::getMatchType, matchType)
                .eq(ViolationTerm::getTerm, term)) > 0;
    }
}
