package com.gift.gift.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("monitoring")
public class MonitoringSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain monitoringSecurityFilterChain(
            HttpSecurity http,
            Environment environment,
            @Value("${MONITORING_PASSWORD}") String password
    ) throws Exception {
        if (password.isBlank() || password.length() < 32) {
            throw new IllegalArgumentException("MONITORING_PASSWORD must contain at least 32 characters");
        }

        // Keep monitoring credentials in this chain, outside the user JWT and AI service chains.
        var users = new InMemoryUserDetailsManager(User.withUsername("prometheus")
                .password("{noop}" + password)
                .roles("MONITORING")
                .build());
        http.securityMatcher("/actuator/**")
                .authenticationProvider(new DaoAuthenticationProvider(users))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").access((authentication, context) ->
                                new AuthorizationDecision(
                                        context.getRequest().getLocalPort() == environment.getProperty(
                                                "local.management.port", Integer.class, -1)
                                        && authentication.get().getAuthorities().stream().anyMatch(
                                                authority -> authority.getAuthority().equals("ROLE_MONITORING"))))
                        .anyRequest().denyAll());
        return http.build();
    }
}
