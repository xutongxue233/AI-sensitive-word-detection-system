package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.AiProperties.ApiType;
import com.ai.moderation.config.ClipProperties;
import com.ai.moderation.domain.AppSetting;
import com.ai.moderation.dto.SettingsResponse;
import com.ai.moderation.dto.SettingsUpdateRequest;
import com.ai.moderation.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettingsServiceTest {
    private AppSettingRepository repository;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        AiProperties aiDefaults = new AiProperties(false, ApiType.CHAT, "http://localhost:11434", "", "qwen2.5", 0, 0.6, 60);
        ClipProperties clipDefaults = new ClipProperties(0.2, false);
        service = new SettingsService(repository, aiDefaults, clipDefaults);
        when(repository.findById(1L)).thenReturn(Optional.empty());
    }

    @Test
    void seedsDefaultsFromConfigWhenRowMissing() {
        SettingsResponse response = service.currentResponse();

        assertThat(response.aiEnabled()).isFalse();
        assertThat(response.aiApiType()).isEqualTo("CHAT");
        assertThat(response.aiBaseUrl()).isEqualTo("http://localhost:11434");
        assertThat(response.aiApiKeyConfigured()).isFalse();
        assertThat(response.aiModel()).isEqualTo("qwen2.5");
        assertThat(response.aiConfidenceThreshold()).isEqualTo(0.6);
        assertThat(response.clipPaddingSeconds()).isEqualTo(0.2);
        assertThat(response.clipPreciseExport()).isFalse();
        verify(repository).insert(any(AppSetting.class));
    }

    @Test
    void updateMergesProvidedFieldsAndExposesKeyConfiguredFlag() {
        SettingsResponse response = service.update(new SettingsUpdateRequest(
                true, "responses", "https://api.example.com/v1", "sk-secret", "gpt-4o-mini",
                0.2, 0.8, 30, 0.35, true
        ));

        assertThat(response.aiEnabled()).isTrue();
        assertThat(response.aiApiType()).isEqualTo("RESPONSES");
        assertThat(response.aiBaseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(response.aiApiKeyConfigured()).isTrue();
        assertThat(response.aiModel()).isEqualTo("gpt-4o-mini");
        assertThat(response.aiConfidenceThreshold()).isEqualTo(0.8);
        assertThat(response.aiTimeoutSeconds()).isEqualTo(30);
        assertThat(response.clipPaddingSeconds()).isEqualTo(0.35);
        assertThat(response.clipPreciseExport()).isTrue();
        verify(repository).updateById(any(AppSetting.class));
    }

    @Test
    void nullApiKeyKeepsExistingKey() {
        service.update(new SettingsUpdateRequest(null, null, null, "sk-keep", null, null, null, null, null, null));
        SettingsResponse response = service.update(
                new SettingsUpdateRequest(null, null, null, null, "another-model", null, null, null, null, null));

        assertThat(response.aiApiKeyConfigured()).isTrue();
        assertThat(response.aiModel()).isEqualTo("another-model");
    }

    @Test
    void confidenceThresholdIsClampedToUnitInterval() {
        SettingsResponse response = service.update(
                new SettingsUpdateRequest(null, null, null, null, null, null, 1.8, null, null, null));

        assertThat(response.aiConfidenceThreshold()).isEqualTo(1.0);
    }
}
