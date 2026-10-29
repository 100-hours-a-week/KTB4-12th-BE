package com.gift.gift.infrastructure.redis;

import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean(name = {"redisConnectionFactory", "authRedisConnectionFactory"})
    @Primary
    public LettuceConnectionFactory authRedisConnectionFactory(
            @Value("${app.redis.auth.host}") String host,
            @Value("${app.redis.auth.port}") int port,
            @Value("${app.redis.auth.password:}") String password,
            @Value("${app.redis.auth.command-timeout:2s}") Duration commandTimeout,
            @Value("${app.redis.auth.connect-timeout:2s}") Duration connectTimeout
    ) {
        return createConnectionFactory(
                host,
                port,
                password,
                commandTimeout,
                connectTimeout
        );
    }

    @Bean("productRedisConnectionFactory")
    public LettuceConnectionFactory productRedisConnectionFactory(
            @Value("${app.redis.product.host}") String host,
            @Value("${app.redis.product.port}") int port,
            @Value("${app.redis.product.password:}") String password,
            @Value("${app.redis.product.command-timeout:2s}") Duration commandTimeout,
            @Value("${app.redis.product.connect-timeout:2s}") Duration connectTimeout
    ) {
        return createConnectionFactory(
                host,
                port,
                password,
                commandTimeout,
                connectTimeout
        );
    }

    @Bean(name = {"stringRedisTemplate", "authRedisTemplate"})
    @Primary
    public StringRedisTemplate authRedisTemplate(
            @Qualifier("authRedisConnectionFactory") RedisConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean("productRedisTemplate")
    public StringRedisTemplate productRedisTemplate(
            @Qualifier("productRedisConnectionFactory") RedisConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    private LettuceConnectionFactory createConnectionFactory(
            String host,
            int port,
            String password,
            Duration commandTimeout,
            Duration connectTimeout
    ) {
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(host, port);

        if (password != null && !password.isBlank()) {
            redisConfiguration.setPassword(RedisPassword.of(password));
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(
                        SocketOptions.builder()
                                .connectTimeout(connectTimeout)
                                .build()
                )
                .build();

        LettuceClientConfiguration clientConfiguration =
                LettuceClientConfiguration.builder()
                        .commandTimeout(commandTimeout)
                        .clientOptions(clientOptions)
                        .build();

        return new LettuceConnectionFactory(
                redisConfiguration,
                clientConfiguration
        );
    }
}
