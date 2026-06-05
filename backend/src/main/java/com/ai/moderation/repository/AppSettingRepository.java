package com.ai.moderation.repository;

import com.ai.moderation.common.mybatis.BaseCrudMapper;
import com.ai.moderation.domain.AppSetting;

/**
 * {@code app_settings} 表读写,对应实体 {@link AppSetting}。
 *
 * <p>该表为单行设计,承载运行时可变设置(AI 端点/密钥/模型、置信度阈值、剪辑参数等)。
 * 上层 {@code SettingsService} 在内存缓存的基础上,通过本 mapper 完成设置的加载与持久化;
 * 前端齿轮改动对新建任务即时生效。读写均为标准 CRUD,无额外条件方法。</p>
 */
public interface AppSettingRepository extends BaseCrudMapper<AppSetting> {
}
