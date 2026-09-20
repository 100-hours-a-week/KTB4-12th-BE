package com.gift.gift.global.config;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.gift.gift.global.security.ActiveUserJwtAuthenticationConverter;
import com.gift.gift.global.security.ApiAuthenticationEntryPoint;
import com.gift.gift.global.security.CorsProperties;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint entryPoint,
            ActiveUserJwtAuthenticationConverter converter,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        RequestMatcher csrfIgnoredApi =
                request -> !isRefreshCookieEndpoint(request);

        http.cors(cors -> cors
                .configurationSource(corsConfigurationSource)
        );

        // 서버의 HTTP 세션에 로그인 상태를 저장하지 않음 -> stateless
        // 보호된 API 요청이 들어올 때마다 Authorization 헤더의 엑세스 토큰을 검증
        http.sessionManagement(session -> session
                .sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                )
        );

        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        http.logout(logout -> logout.disable());
        http.requestCache(cache -> cache.disable());

        http.csrf(csrf -> csrf
                .ignoringRequestMatchers(csrfIgnoredApi)
        );

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/auth/terms")
                .permitAll()
                .requestMatchers(
                        HttpMethod.POST,
                        "/auth/email-availability",
                        "/auth/signup",
                        "/auth/login"
                )
                .permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/**",
                        "/products"
                ).permitAll()
                .anyRequest()
                .authenticated()
        );

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(entryPoint)
        );

        http.oauth2ResourceServer(resourceServer -> resourceServer
                .authenticationEntryPoint(entryPoint)
                .jwt(jwt -> jwt
                        .jwtAuthenticationConverter(converter)
                )
        );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            CorsProperties properties
    ) {
        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                properties.allowedOrigins()
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PATCH",
                        "PUT",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "Idempotency-Key"
                )
        );

        configuration.setExposedHeaders(
                List.of("Retry-After")
        );

        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }

    private boolean isRefreshCookieEndpoint(
            HttpServletRequest request
    ) {
        String path = request.getServletPath();

        return "/auth/refresh".equals(path)
                || "/auth/logout".equals(path);
    }
}
