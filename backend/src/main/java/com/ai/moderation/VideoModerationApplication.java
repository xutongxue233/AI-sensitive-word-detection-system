package com.ai.moderation;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.AsrProperties;
import com.ai.moderation.config.ClipProperties;
import com.ai.moderation.config.FfmpegProperties;
import com.ai.moderation.config.StorageProperties;
import com.ai.moderation.config.SubtitleOcrProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@MapperScan("com.ai.moderation.repository")
@SpringBootApplication
@EnableConfigurationProperties({
        StorageProperties.class,
        FfmpegProperties.class,
        AsrProperties.class,
        AiProperties.class,
        ClipProperties.class,
        SubtitleOcrProperties.class
})
public class VideoModerationApplication {
    public static void main(String[] args) {
        SpringApplication.run(VideoModerationApplication.class, args);
    }
}
