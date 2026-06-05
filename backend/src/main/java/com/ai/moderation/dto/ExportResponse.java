package com.ai.moderation.dto;

/**
 * 导出结果响应。一次导出按命中来源对视频做删音频段或去字幕处理后,返回产物位置与处理片段统计。
 *
 * @param videoId         被导出的视频主键
 * @param jobId           触发本次导出的检测任务主键
 * @param exportPath      导出产物的存储路径
 * @param removedClipCount 本次实际删除/去字幕处理的片段数
 */
public record ExportResponse(
        Long videoId,
        Long jobId,
        String exportPath,
        int removedClipCount
) {
}

