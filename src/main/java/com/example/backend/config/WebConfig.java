package com.example.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS（Cross-Origin Resource Sharing）の設定クラス
 * フロントエンドが異なるオリジンからAPIを呼び出せるように許可オリジンを設定する
 */
@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        // ローカル開発環境（Vite デフォルトポート）と GitHub Pages の本番環境を許可
                        .allowedOrigins("http://localhost:5173", "https://Akifumi1119.github.io")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }
}
