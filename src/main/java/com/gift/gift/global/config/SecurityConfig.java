package com.gift.gift.global.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.gift.gift.global.security.ActiveUserJwtAuthenticationConverter;
import com.gift.gift.global.security.ApiAuthenticationEntryPoint;
import com.gift.gift.global.security.CorsProperties;
import com.gift.gift.global.security.RefreshCookieOriginFilter;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint entryPoint,
            ActiveUserJwtAuthenticationConverter converter,
            RefreshCookieOriginFilter refreshCookieOriginFilter,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http.cors(cors -> cors
                .configurationSource(corsConfigurationSource)
        );

        /*
         * 일반 보호 API는 Authorization Bearer Token을 사용하고,
         * Refresh Cookie 기반 API는 RefreshCookieOriginFilter에서
         * Origin을 검증하므로 Spring 기본 CSRF 기능은 비활성화한다.
         */
        http.csrf(AbstractHttpConfigurer::disable);

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );

        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);

        /*
         * Refresh Cookie를 사용하는 API의 Origin 검사를
         * Spring CORS 처리보다 먼저 수행해 실패 시에도
         * 프로젝트 공통 JSON 오류 응답을 반환한다.
         */
        http.addFilterBefore(
                refreshCookieOriginFilter,
                CorsFilter.class
        );

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                        HttpMethod.GET,
                        "/auth/terms"
                )
                .permitAll()
                .requestMatchers(
                        HttpMethod.POST,
                        "/auth/email-availability",
                        "/auth/signup",
                        "/auth/login",
                        "/auth/refresh",
                        "/auth/logout"
                )
                .permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/**",
                        "/products"
                )
                .permitAll()
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

    /*
     * RefreshCookieOriginFilter가 @Component이므로 Spring Boot가
     * Servlet Filter로 자동 등록할 수 있다.
     *
     * SecurityFilterChain에 직접 등록했으므로 자동 등록을 끄고,
     * 필터가 두 경로로 실행되는 것을 방지한다.
     */
    @Bean
    public FilterRegistrationBean<RefreshCookieOriginFilter>
    refreshCookieOriginFilterRegistration(
            RefreshCookieOriginFilter filter
    ) {
        FilterRegistrationBean<RefreshCookieOriginFilter> registration =
                new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);

        return registration;
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
}
