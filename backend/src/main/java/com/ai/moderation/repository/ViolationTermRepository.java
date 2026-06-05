package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ViolationTerm;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code violation_terms} 表读写,对应实体 {@link ViolationTerm}。
 *
 * <p>违规词库是规则召回阶段的核心输入:每条词带匹配类型({@link MatchType}:精确/变体/正则/语义)。
 * 词库整体在 AI 整篇提取时随字幕一并交给模型;启用项也供逐条规则匹配召回候选。</p>
 */
public interface ViolationTermRepository extends BaseCrudMapper<ViolationTerm> {

    /**
     * 取全部启用的违规词,按更新时间倒序。
     *
     * @return 启用状态的违规词列表
     */
    default List<ViolationTerm> findByEnabledTrueOrderByUpdatedAtDesc() {
        return selectList(Wrappers.lambdaQuery(ViolationTerm.class)
                .eq(ViolationTerm::isEnabled, true)
                .orderByDesc(ViolationTerm::getUpdatedAt));
    }

    /**
     * 按词或分类模糊搜索,按更新时间倒序,供前端词库检索。
     *
     * <p>方法名中的 IgnoreCase(大小写不敏感)由列的 collation(排序规则)保证,
     * 非代码层处理;是否忽略大小写实际取决于 DB 列排序规则。</p>
     *
     * @param term     词关键字;为空时按空串匹配(不约束该条件)
     * @param category 分类关键字;为空时按空串匹配
     * @return 命中关键字的违规词列表
     */
    default List<ViolationTerm> findByTermContainingIgnoreCaseOrCategoryContainingIgnoreCaseOrderByUpdatedAtDesc(String term, String category) {
        String keyword = term == null ? "" : term;
        return selectList(Wrappers.lambdaQuery(ViolationTerm.class)
                .like(ViolationTerm::getTerm, keyword)
                .or()
                .like(ViolationTerm::getCategory, category == null ? "" : category)
                .orderByDesc(ViolationTerm::getUpdatedAt));
    }

    /**
     * 按 term + matchType 联合查重,用于新增违规词时去重。
     *
     * <p>大小写敏感性同样取决于列 collation,而非代码层处理。</p>
     *
     * @param term      违规词文本
     * @param matchType 匹配类型
     * @return 已存在同 term 同 matchType 的记录则为 {@code true}
     */
    default boolean existsByTermIgnoreCaseAndMatchType(String term, MatchType matchType) {
        return selectCount(Wrappers.lambdaQuery(ViolationTerm.class)
                .eq(ViolationTerm::getMatchType, matchType)
                .eq(ViolationTerm::getTerm, term)) > 0;
    }
}
