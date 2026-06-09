package com.ai.moderation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域(CORS)配置。后端作为本地工具服务使用,放开全部来源、方法与请求头,便于前端
 * 从任意开发地址或内网穿透域名访问 API、视频流与下载接口。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition", "Content-Range", "Accept-Ranges")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
