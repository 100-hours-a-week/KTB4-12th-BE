package com.gift.gift.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        var emailAvailability = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/auth/email-availability");

        var signup = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/auth/signup");

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/auth/terms")
                .permitAll()
                .requestMatchers(
                        HttpMethod.POST,
                        "/auth/email-availability",
                        "/auth/signup"
                )
                .permitAll()
                .anyRequest()
                .authenticated()
        );

        http.csrf(csrf -> csrf
                .ignoringRequestMatchers(emailAvailability, signup)
        );

        return http.build();
    }
}
