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
    private String aiApiType;
    private String aiBaseUrl;
    private String aiApiKey;
    private String aiModel;
    private Double aiTemperature;
    private Double aiConfidenceThreshold;
    private Integer aiTimeoutSeconds;

    private Double clipPaddingSeconds;
    private Boolean clipPreciseExport;

    private Instant updatedAt = Instant.now();
}
