package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 运行时系统设置(单行,id 固定为 1)。
 * 用于让 AI 复核与剪辑参数可在前台即时调整,无需改配置文件重启。
 * 首次访问时由 SettingsService 用 application.yml 的默认值种子化。
 */
@Getter
@Setter
@TableName("app_settings")
public class AppSetting implements Identifiable {
    @TableId(type = IdType.INPUT)
    private Long id;

    private Boolean aiEnabled;
    /** OpenAI 兼容客户端的端点形态:CHAT 或 RESPONSES,取值见 {@link com.ai.moderation.config.ApiType}。 */
    private String aiApiType;
    private String aiBaseUrl;
    private String aiApiKey;
    private String aiModel;
    private Double aiTemperature;
    /** 置信度把关阈值(默认 0.6),命中需 violation=true 且 confidence 不低于此值才判违规。 */
    private Double aiConfidenceThreshold;
    private Integer aiTimeoutSeconds;

    /** 剪辑/去字幕导出时命中时段前后的留白秒数。 */
    private Double clipPaddingSeconds;
    /** 是否启用精确导出(按命中精确时点切割,而非按段对齐)。 */
    private Boolean clipPreciseExport;

    private Instant updatedAt = Instant.now();
}
