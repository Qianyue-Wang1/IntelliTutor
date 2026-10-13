package com.intellitutor.scut.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 全局配置。
 *
 * <p>
 * 注册 JWT 鉴权拦截器并配置 CORS 跨域策略。
 *
 * <p>
 * <b>鉴权白名单：</b>
 * <ul>
 * <li>{@code /v1/auth/**} —— 注册、登录、Token 刷新（公开接口）</li>
 * <li>{@code /error} —— Spring Boot 内置错误页</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Value("${intellitutor.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174}")
    private String[] allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/v1/**") // 拦截所有 /v1 路由
                .excludePathPatterns(
                        "/v1/auth/**", // 注册、登录、刷新 Token
                        "/v1/config/capabilities", // 公开产品能力，供首页与登录前界面使用
                        "/error" // Spring Boot 错误页
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
