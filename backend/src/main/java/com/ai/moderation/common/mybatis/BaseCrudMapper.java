package com.ai.moderation.common.mybatis;

import com.ai.moderation.domain.Identifiable;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    default T save(T entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<T> saveAll(Collection<T> entities) {
        for (T entity : entities) {
            save(entity);
        }
        return List.copyOf(entities);
    }
}
