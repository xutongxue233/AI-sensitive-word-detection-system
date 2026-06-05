package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 剪辑与导出参数,绑定 {@code app.clip.*}。
 *
 * <p>与 {@link AiProperties} 同理:这里的值仅在 {@code app_settings} 表无记录时种子化,
 * 运行时实际生效值由 {@link com.ai.moderation.service.SettingsService} 提供,前端可即时修改、对新建任务生效。
 *
 * @param paddingSeconds 命中片段前后各保留的留白秒数,默认 0.2,避免切口处吞字或突兀
 * @param preciseExport  导出模式:true 为精确(逐帧重编码,慢但切口准),false 为快速(流复制,快但可能对齐到关键帧)
 */
@ConfigurationProperties(prefix = "app.clip")
public record ClipProperties(double paddingSeconds, boolean preciseExport) {
    /** 紧凑构造器:留白为负属非法配置,回退到默认 0.2 秒。 */
    public ClipProperties {
        if (paddingSeconds < 0) {
            paddingSeconds = 0.2;
        }
    }
}
