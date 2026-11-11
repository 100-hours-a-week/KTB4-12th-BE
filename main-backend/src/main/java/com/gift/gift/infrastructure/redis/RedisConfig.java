package com.gift.gift.infrastructure.redis;

import java.net.URI;
import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.SocketOptions;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean(name = {"redisConnectionFactory", "authRedisConnectionFactory"})
    @Primary
    public LettuceConnectionFactory authRedisConnectionFactory(
            @Value("${app.redis.auth.host}") String host,
            @Value("${app.redis.auth.port}") int port,
            @Value("${app.redis.auth.password:}") String password,
            @Value("${app.redis.auth.command-timeout:2s}") Duration commandTimeout,
            @Value("${app.redis.auth.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.redis.auth.sentinel.enabled:false}") boolean sentinelEnabled,
            @Value("${app.redis.auth.sentinel.master:}") String sentinelMaster,
            @Value("${app.redis.auth.sentinel.nodes:}") String sentinelNodes,
            @Value("${app.redis.auth.sentinel.password:}") String sentinelPassword
    ) {
        return createConnectionFactory(
                host,
                port,
                password,
                commandTimeout,
                connectTimeout,
                sentinelEnabled,
                sentinelMaster,
                sentinelNodes,
                sentinelPassword
        );
    }

    @Bean("productRedisConnectionFactory")
    public LettuceConnectionFactory productRedisConnectionFactory(
            @Value("${app.redis.product.host}") String host,
            @Value("${app.redis.product.port}") int port,
            @Value("${app.redis.product.password:}") String password,
            @Value("${app.redis.product.command-timeout:2s}") Duration commandTimeout,
            @Value("${app.redis.product.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.redis.product.sentinel.enabled:false}") boolean sentinelEnabled,
            @Value("${app.redis.product.sentinel.master:}") String sentinelMaster,
            @Value("${app.redis.product.sentinel.nodes:}") String sentinelNodes,
            @Value("${app.redis.product.sentinel.password:}") String sentinelPassword
    ) {
        return createConnectionFactory(
                host,
                port,
                password,
                commandTimeout,
                connectTimeout,
                sentinelEnabled,
                sentinelMaster,
                sentinelNodes,
                sentinelPassword
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
            Duration connectTimeout,
            boolean sentinelEnabled,
            String sentinelMaster,
            String sentinelNodes,
            String sentinelPassword
    ) {
        RedisConfiguration redisConfiguration = createRedisConfiguration(
                host, port, password, sentinelEnabled, sentinelMaster, sentinelNodes, sentinelPassword
        );

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(
                        SocketOptions.builder()
                                .connectTimeout(connectTimeout)
                                .build()
                )
                .build();

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientBuilder =
                LettuceClientConfiguration.builder()
                        .commandTimeout(commandTimeout)
                        .clientOptions(clientOptions);

        if (sentinelEnabled) {
            // Sentinel 토폴로지 변경을 추적하되 Replica의 지연된 인증·캐시 값은 읽지 않는다.
            clientBuilder.readFrom(ReadFrom.MASTER);
        }
        LettuceClientConfiguration clientConfiguration = clientBuilder.build();

        return new LettuceConnectionFactory(
                redisConfiguration,
                clientConfiguration
        );
    }

    private RedisConfiguration createRedisConfiguration(
            String host,
            int port,
            String password,
            boolean sentinelEnabled,
            String sentinelMaster,
            String sentinelNodes,
            String sentinelPassword
    ) {
        if (!sentinelEnabled) {
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
            if (StringUtils.hasText(password)) {
                configuration.setPassword(RedisPassword.of(password));
            }
            return configuration;
        }

        // 설정 누락 시 localhost로 우회하지 않고 기동을 거부한다.
        Assert.hasText(sentinelMaster, "Redis Sentinel master 설정이 필요합니다.");
        Assert.hasText(sentinelNodes, "Redis Sentinel nodes 설정이 필요합니다.");
        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
        configuration.master(sentinelMaster.trim());
        for (String nodeAddress : sentinelNodes.split(",", -1)) {
            configuration.addSentinel(parseSentinelNode(nodeAddress.trim()));
        }
        if (StringUtils.hasText(password)) {
            configuration.setPassword(RedisPassword.of(password));
        }
        // 데이터 노드의 비밀번호와 Sentinel 접속 비밀번호는 별개다.
        if (StringUtils.hasText(sentinelPassword)) {
            configuration.setSentinelPassword(RedisPassword.of(sentinelPassword));
        }
        return configuration;
    }

    private RedisNode parseSentinelNode(String nodeAddress) {
        try {
            URI address = URI.create("redis://" + nodeAddress);
            if (address.getHost() != null && address.getPort() > 0 && address.getPort() <= 65535
                    && address.getRawUserInfo() == null && address.getRawPath().isEmpty()
                    && address.getRawQuery() == null && address.getRawFragment() == null) {
                String host = address.getHost();
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                return new RedisNode(host, address.getPort());
            }
        } catch (IllegalArgumentException ignored) {
            // 입력값에 인증 정보가 섞였을 수 있으므로 오류에 원문을 포함하지 않는다.
        }
        throw new IllegalArgumentException("Redis Sentinel nodes는 쉼표로 구분한 host:port 형식이어야 합니다.");
    }
}
