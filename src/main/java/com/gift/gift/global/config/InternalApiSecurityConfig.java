package com.gift.gift.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import com.gift.gift.domain.recommendation.support.AiProfilingProperties;
import com.gift.gift.global.security.ApiAuthenticationEntryPoint;
import com.gift.gift.global.security.SecurityErrorResponseWriter;
import com.gift.gift.global.security.ServiceTokenAuthenticationFilter;

@Configuration
public class InternalApiSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain internalApiSecurityFilterChain(
            HttpSecurity http,
            AiProfilingProperties properties,
            ApiAuthenticationEntryPoint entryPoint,
            SecurityErrorResponseWriter responseWriter
    ) throws Exception {
        http.securityMatcher("/api/internal/v1/**");

        http.csrf(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(entryPoint)
        );

        // 내부 체인에서만 생성해 Servlet Filter 자동 등록을 방지한다.
        http.addFilterBefore(
                new ServiceTokenAuthenticationFilter(
                        properties.serviceToken(),
                        responseWriter
                ),
                AnonymousAuthenticationFilter.class
        );

        http.authorizeHttpRequests(authorize -> authorize
                .anyRequest()
                .hasAuthority(ServiceTokenAuthenticationFilter.AUTHORITY)
        );

        return http.build();
    }
}
