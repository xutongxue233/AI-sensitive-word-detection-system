package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.ApiType;
import com.ai.moderation.config.AsrProvider;
import com.ai.moderation.config.ClipProperties;
import com.ai.moderation.common.ApiException;
import com.ai.moderation.domain.AppSetting;
import com.ai.moderation.dto.SettingsResponse;
import com.ai.moderation.dto.SettingsUpdateRequest;
import com.ai.moderation.repository.AppSettingRepository;
import com.ai.moderation.service.support.AsrOnlineSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * 运行时系统设置中心。设置持久化在 app_settings 单行(id=1),支持前台即时调整。
 * 首次访问若无记录,则用 application.yml 的 AiProperties/ClipProperties 默认值种子化。
 * 内存缓存当前设置,更新时刷新,避免 AI 复核逐条命中时频繁查库。
 */
@Service
public class SettingsService {
    private static final Long ROW_ID = 1L;
    /** 在线 ASR 默认端点/模型(小米 MiMo),DB 未配置时兜底,亦作为种子值。 */
    private static final String DEFAULT_ASR_ONLINE_BASE_URL = "https://api.xiaomimimo.com";
    private static final String DEFAULT_ASR_ONLINE_MODEL = "mimo-v2.5-asr";

    private final AppSettingRepository repository;
    private final AiProperties aiDefaults;
    private final ClipProperties clipDefaults;
    private volatile AppSetting cache;

    public SettingsService(AppSettingRepository repository, AiProperties aiDefaults, ClipProperties clipDefaults) {
        this.repository = repository;
        this.aiDefaults = aiDefaults;
        this.clipDefaults = clipDefaults;
    }

    /** 当前 AI 设置投影:把 {@link AppSetting} 行映射为不可变 {@link AiProperties},各项为空时回落到内置默认值。 */
    public AiProperties currentAi() {
        AppSetting s = load();
        return new AiProperties(
                Boolean.TRUE.equals(s.getAiEnabled()),
                parseApiType(s.getAiApiType()),
                s.getAiBaseUrl(),
                s.getAiApiKey(),
                s.getAiModel(),
                s.getAiTemperature() == null ? 0 : s.getAiTemperature(),
                s.getAiConfidenceThreshold() == null ? 0.6 : s.getAiConfidenceThreshold(),
                s.getAiTimeoutSeconds() == null ? 60 : s.getAiTimeoutSeconds()
        );
    }

    /** 当前剪辑设置投影:把 {@link AppSetting} 行映射为不可变 {@link ClipProperties},为空时回落默认值。 */
    public ClipProperties currentClip() {
        AppSetting s = load();
        return new ClipProperties(
                s.getClipPaddingSeconds() == null ? 0.2 : s.getClipPaddingSeconds(),
                Boolean.TRUE.equals(s.getClipPreciseExport())
        );
    }

    /** 当前 ASR 引擎设置投影:provider 缺省 LOCAL,在线端点/模型为空时回落 MiMo 默认值。 */
    public AsrOnlineSettings currentAsr() {
        AppSetting s = load();
        return new AsrOnlineSettings(
                parseAsrProvider(s.getAsrProvider()),
                StringUtils.hasText(s.getAsrOnlineBaseUrl()) ? s.getAsrOnlineBaseUrl() : DEFAULT_ASR_ONLINE_BASE_URL,
                s.getAsrOnlineApiKey(),
                StringUtils.hasText(s.getAsrOnlineModel()) ? s.getAsrOnlineModel() : DEFAULT_ASR_ONLINE_MODEL
        );
    }

    /** 面向前台的设置快照:出于安全不回传明文 ApiKey,仅以 {@code aiApiKeyConfigured} 标记是否已配置。 */
    public SettingsResponse currentResponse() {
        AppSetting s = load();
        AiProperties ai = currentAi();
        ClipProperties clip = currentClip();
        AsrOnlineSettings asr = currentAsr();
        return new SettingsResponse(
                ai.enabled(),
                ai.apiType().name(),
                ai.baseUrl(),
                StringUtils.hasText(s.getAiApiKey()),
                ai.model(),
                ai.temperature(),
                ai.confidenceThreshold(),
                ai.timeoutSeconds(),
                asr.provider().name(),
                asr.baseUrl(),
                StringUtils.hasText(s.getAsrOnlineApiKey()),
                asr.model(),
                clip.paddingSeconds(),
                clip.preciseExport()
        );
    }

    /** 更新设置:仅覆盖请求中非空字段,置信度阈值钳到 [0,1]、超时/留白做下限保护,落库后刷新内存缓存。 */
    @Transactional
    public SettingsResponse update(SettingsUpdateRequest request) {
        AppSetting s = load();
        if (request.aiEnabled() != null) {
            s.setAiEnabled(request.aiEnabled());
        }
        if (request.aiApiType() != null) {
            s.setAiApiType(parseApiType(request.aiApiType()).name());
        }
        if (request.aiBaseUrl() != null) {
            s.setAiBaseUrl(request.aiBaseUrl().trim());
        }
        if (request.aiApiKey() != null) {
            s.setAiApiKey(request.aiApiKey().trim());
        }
        if (request.aiModel() != null) {
            s.setAiModel(request.aiModel().trim());
        }
        if (request.aiTemperature() != null) {
            s.setAiTemperature(request.aiTemperature());
        }
        if (request.aiConfidenceThreshold() != null) {
            s.setAiConfidenceThreshold(clamp(request.aiConfidenceThreshold(), 0, 1));
        }
        if (request.aiTimeoutSeconds() != null) {
            s.setAiTimeoutSeconds(Math.max(1, request.aiTimeoutSeconds()));
        }
        if (request.asrProvider() != null) {
            s.setAsrProvider(parseAsrProvider(request.asrProvider()).name());
        }
        if (request.asrOnlineBaseUrl() != null) {
            String previousBaseUrl = canonicalUrl(s.getAsrOnlineBaseUrl());
            String nextBaseUrl = normalizeAsrBaseUrl(request.asrOnlineBaseUrl());
            s.setAsrOnlineBaseUrl(nextBaseUrl);
            // A key may only be reused for the endpoint it was entered for. Changing the
            // endpoint without a new key must invalidate the stored credential.
            if (!Objects.equals(previousBaseUrl, canonicalUrl(nextBaseUrl))
                    && request.asrOnlineApiKey() == null) {
                s.setAsrOnlineApiKey(null);
            }
        }
        if (request.asrOnlineApiKey() != null) {
            s.setAsrOnlineApiKey(request.asrOnlineApiKey().trim());
        }
        if (request.asrOnlineModel() != null) {
            s.setAsrOnlineModel(request.asrOnlineModel().trim());
        }
        if (request.clipPaddingSeconds() != null) {
            s.setClipPaddingSeconds(Math.max(0, request.clipPaddingSeconds()));
        }
        if (request.clipPreciseExport() != null) {
            s.setClipPreciseExport(request.clipPreciseExport());
        }
        s.setUpdatedAt(Instant.now());
        repository.updateById(s);
        cache = s;
        return currentResponse();
    }

    /** 懒加载当前设置:volatile 缓存 + synchronized 双重检查锁,避免每次 AI 调用查库;首次无记录则 {@link #seedDefaults}。 */
    private AppSetting load() {
        AppSetting current = cache;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            AppSetting row = repository.findById(ROW_ID).orElseGet(this::seedDefaults);
            cache = row;
            return row;
        }
    }

    /** 仅当 DB 无记录时,用 application.yml 的 {@link AiProperties}/{@link ClipProperties} 默认值落库种子行。 */
    private AppSetting seedDefaults() {
        AppSetting row = new AppSetting();
        row.setId(ROW_ID);
        row.setAiEnabled(aiDefaults.enabled());
        row.setAiApiType(aiDefaults.apiType().name());
        row.setAiBaseUrl(aiDefaults.baseUrl());
        row.setAiApiKey(aiDefaults.apiKey());
        row.setAiModel(aiDefaults.model());
        row.setAiTemperature(aiDefaults.temperature());
        row.setAiConfidenceThreshold(aiDefaults.confidenceThreshold());
        row.setAiTimeoutSeconds(aiDefaults.timeoutSeconds());
        row.setAsrProvider(AsrProvider.LOCAL.name());
        row.setAsrOnlineBaseUrl(DEFAULT_ASR_ONLINE_BASE_URL);
        row.setAsrOnlineModel(DEFAULT_ASR_ONLINE_MODEL);
        row.setClipPaddingSeconds(clipDefaults.paddingSeconds());
        row.setClipPreciseExport(clipDefaults.preciseExport());
        row.setUpdatedAt(Instant.now());
        repository.insert(row);
        return row;
    }

    private ApiType parseApiType(String value) {
        if (value == null) {
            return ApiType.CHAT;
        }
        try {
            return ApiType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ApiType.CHAT;
        }
    }

    private AsrProvider parseAsrProvider(String value) {
        if (value == null) {
            return AsrProvider.LOCAL;
        }
        try {
            return AsrProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AsrProvider.LOCAL;
        }
    }

    /** 在线 ASR 只允许 HTTPS；本机回环地址允许 HTTP 以支持本地兼容服务。 */
    private String normalizeAsrBaseUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            boolean loopbackHttp = "http".equals(scheme)
                    && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "[::1]".equals(host)
                    || "::1".equals(host));
            if (!("https".equals(scheme) || loopbackHttp)
                    || host == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "在线 ASR Base URL 必须是 HTTPS 地址（本机回环地址可使用 HTTP），且不得包含用户信息、查询参数或片段");
            }
            return trimmed.replaceAll("/+$", "");
        } catch (URISyntaxException ex) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "在线 ASR Base URL 格式无效");
        }
    }

    private String canonicalUrl(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
