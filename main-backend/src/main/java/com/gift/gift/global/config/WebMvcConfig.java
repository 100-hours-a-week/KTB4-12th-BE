package com.gift.gift.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.gift.gift.domain.auth.web.LoginIpRateLimitInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginIpRateLimitInterceptor
            loginIpRateLimitInterceptor;

    @Override
    public void addInterceptors(
            InterceptorRegistry registry
    ) {
        registry.addInterceptor(
                loginIpRateLimitInterceptor
        ).addPathPatterns("/auth/login");
    }
}
