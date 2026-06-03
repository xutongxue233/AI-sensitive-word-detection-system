package com.ai.moderation.repository;

import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface VideoFileRepository extends BaseCrudMapper<VideoFile> {
    default List<VideoFile> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.lambdaQuery(VideoFile.class)
                .orderByDesc(VideoFile::getCreatedAt)
                .orderByDesc(VideoFile::getId));
    }
}
