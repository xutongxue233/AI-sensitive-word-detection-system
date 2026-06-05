package com.ai.moderation.common.mybatis;

import com.ai.moderation.domain.Identifiable;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 全项目 mapper 的公共基接口。在 MyBatis-Plus 的 {@link BaseMapper} 之上叠加一层
 * Spring-Data 风格的便捷方法(findById/findAll/existsById/save/saveAll),让业务层无需直面
 * selectById/insert/updateById 等原始命名。要求实体实现 {@link Identifiable} 以便统一按主键判断 upsert。
 *
 * @param <T> 受管实体类型,必须实现 {@link Identifiable} 暴露主键
 */
public interface BaseCrudMapper<T extends Identifiable> extends BaseMapper<T> {
    default Optional<T> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default List<T> findAll() {
        return selectList(Wrappers.emptyWrapper());
    }

    default boolean existsById(Long id) {
        return selectById(id) != null;
    }

    /**
     * upsert 语义:主键为空视为新增(insert),否则按主键更新(updateById)。
     * 屏蔽"先查再判断"的样板,业务层只管 save。
     */
    default T save(T entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    /** 批量保存,逐条复用 {@link #save(Identifiable)} 的 upsert 语义;返回入参的不可变副本。 */
    default List<T> saveAll(Collection<T> entities) {
        for (T entity : entities) {
            save(entity);
        }
        return List.copyOf(entities);
    }
}
