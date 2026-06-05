package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.VideoFile;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/**
 * {@code videos} 表读写,对应实体 {@link VideoFile}。
 *
 * <p>视频文件是整条审核管线的输入源,记录上传文件路径、元信息等;一个视频可派生多个检测任务
 * ({@link com.ai.moderation.domain.DetectionJob})。供前端视频列表与任务发起使用。</p>
 */
public interface VideoFileRepository extends BaseCrudMapper<VideoFile> {

    /**
     * 取全部视频,按创建时间倒序。
     *
     * <p>追加 id 倒序作次级排序键:同一创建时间的记录据 id 稳定排序,避免列表顺序抖动。</p>
     *
     * @return 全部视频列表
     */
    default List<VideoFile> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.lambdaQuery(VideoFile.class)
                .orderByDesc(VideoFile::getCreatedAt)
                .orderByDesc(VideoFile::getId));
    }
}
