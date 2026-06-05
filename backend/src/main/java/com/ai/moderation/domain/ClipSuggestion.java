package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 剪辑/去字幕建议实体,对应表 {@code clip_suggestions}。
 * <p>在管线 SUGGESTING_CLIPS 阶段为每条判违规的 {@link TermHit} 生成,供前端确认/调整/导出;
 * 命中来源决定导出方式(音频删片段 vs 画面 delogo 去字幕,见 {@link TranscriptSource})。
 */
@Getter
@Setter
@TableName("clip_suggestions")
public class ClipSuggestion implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外键,所属检测任务 {@link DetectionJob#getId()}。 */
    private Long jobId;

    /** 外键,关联触发本建议的命中 {@link TermHit#getId()}。 */
    private Long hitId;

    /** 建议剪辑/去字幕的起始时间(秒)。 */
    private double startTime;

    /** 建议剪辑/去字幕的结束时间(秒)。 */
    private double endTime;

    /** 命中时段前后留白秒数,导出时向两侧外扩以避免切割过紧。 */
    private double paddingSeconds = 1.0;

    /** 建议状态,取值见 {@link ClipStatus}。 */
    private ClipStatus status = ClipStatus.PENDING;

    /** 导出产物路径,仅在执行导出后才有值。 */
    private String exportPath;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
