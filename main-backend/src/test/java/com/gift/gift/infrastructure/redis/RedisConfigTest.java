package com.gift.gift.infrastructure.redis;

import java.time.Duration;
import java.util.stream.Stream;

import io.lettuce.core.ReadFrom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(RedisConfig.class)
            .withPropertyValues(
                    "app.redis.auth.host=auth.invalid", "app.redis.auth.port=6379",
                    "app.redis.auth.password=auth-data-test-password",
                    "app.redis.product.host=product.invalid", "app.redis.product.port=6380",
                    "app.redis.product.password=product-data-test-password");

    @Test
    @DisplayName("Sentinel 설정이 없으면 기존 Standalone 접속과 Bean 연결을 유지한다")
    void defaultsToStandaloneAndPreservesBeanWiring() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory auth = context.getBean("authRedisConnectionFactory", LettuceConnectionFactory.class);
            LettuceConnectionFactory product = context.getBean(
                    "productRedisConnectionFactory", LettuceConnectionFactory.class);
            assertThat(auth.getSentinelConfiguration()).isNull();
            assertThat(auth.getHostName()).isEqualTo("auth.invalid");
            assertThat(auth.getPort()).isEqualTo(6379);
            assertThat(auth.getPassword()).isEqualTo("auth-data-test-password");
            assertThat(product.getSentinelConfiguration()).isNull();
            assertThat(product.getHostName()).isEqualTo("product.invalid");
            assertThat(product.getPort()).isEqualTo(6380);
            assertThat(product.getPassword()).isEqualTo("product-data-test-password");
            assertThat(context.getBean("redisConnectionFactory")).isSameAs(auth);
            assertThat(context.getBean(RedisConnectionFactory.class)).isSameAs(auth);
            assertThat(context.getBean("stringRedisTemplate")).isSameAs(context.getBean("authRedisTemplate"));
            assertThat(context.getBean("authRedisTemplate", StringRedisTemplate.class).getConnectionFactory())
                    .isSameAs(auth);
            assertThat(context.getBean("productRedisTemplate", StringRedisTemplate.class).getConnectionFactory())
                    .isSameAs(product);
            assertThat(auth.getClientConfiguration().getReadFrom()).isEmpty();
            assertThat(auth.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    @DisplayName("두 그룹은 Sentinel을 공유해도 Primary 이름과 데이터 비밀번호를 각각 사용한다")
    void separatesMastersAndDataPasswordsFromSentinelAuthentication() {
        runner.withPropertyValues(sentinelSettings("auth"))
                .withPropertyValues(sentinelSettings("product"))
                .withPropertyValues("app.redis.auth.command-timeout=4s", "app.redis.auth.connect-timeout=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (String group : new String[]{"auth", "product"}) {
                        LettuceConnectionFactory factory = context.getBean(
                                group + "RedisConnectionFactory", LettuceConnectionFactory.class);
                        RedisSentinelConfiguration configuration = factory.getSentinelConfiguration();
                        assertThat(configuration).isNotNull();
                        assertThat(configuration.getMaster().getName()).isEqualTo("redis-" + group);
                        assertThat(configuration.getSentinels()).containsExactlyInAnyOrder(
                                new RedisNode("sentinel-0.invalid", 26379),
                                new RedisNode("sentinel-1.invalid", 26379),
                                new RedisNode("sentinel-2.invalid", 26379));
                        assertThat(configuration.getPassword().get())
                                .containsExactly((group + "-data-test-password").toCharArray());
                        assertThat(configuration.getSentinelPassword().get())
                                .containsExactly("sentinel-test-password".toCharArray());
                        assertThat(factory.getClientConfiguration().getReadFrom()).contains(ReadFrom.MASTER);
                        assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow().isAutoReconnect())
                                .isTrue();
                    }
                    LettuceConnectionFactory auth = context.getBean(
                            "authRedisConnectionFactory", LettuceConnectionFactory.class);
                    assertThat(auth.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(auth.getClientConfiguration().getClientOptions().orElseThrow()
                            .getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    @DisplayName("상품 Redis만 Sentinel을 켜도 인증 Redis의 기존 연결을 변경하지 않는다")
    void enablesSentinelIndependentlyForEachGroup() {
        runner.withPropertyValues(sentinelSettings("product")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("authRedisConnectionFactory", LettuceConnectionFactory.class)
                    .getSentinelConfiguration()).isNull();
            assertThat(context.getBean("productRedisConnectionFactory", LettuceConnectionFactory.class)
                    .getSentinelConfiguration()).isNotNull();
        });
    }

    @Test
    @DisplayName("비활성 모드에서는 사용하지 않는 Sentinel 설정을 검증하지 않는다")
    void ignoresUnusedSentinelSettingsInStandaloneMode() {
        runner.withPropertyValues("app.redis.auth.sentinel.nodes=not-a-sentinel-address")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("운영 application.yaml의 환경변수로 두 그룹의 Sentinel 설정과 인증을 주입한다")
    void bindsEnvironmentVariablesThroughApplicationYaml() {
        runner.withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("main-application",
                                new FileSystemResource("src/main/resources/application.yaml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("운영 application.yaml을 읽지 못했습니다.", exception);
                    }
                })
                .withPropertyValues(
                        "REDIS_AUTH_SENTINEL_ENABLED=true", "REDIS_AUTH_SENTINEL_MASTER=redis-auth",
                        "REDIS_AUTH_SENTINEL_NODES=sentinel-0.invalid:26379",
                        "REDIS_AUTH_SENTINEL_PASSWORD=auth-sentinel-test-password",
                        "REDIS_PRODUCT_SENTINEL_ENABLED=true", "REDIS_PRODUCT_SENTINEL_MASTER=redis-product",
                        "REDIS_PRODUCT_SENTINEL_NODES=sentinel-1.invalid:26379",
                        "REDIS_PRODUCT_SENTINEL_PASSWORD=product-sentinel-test-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (String group : new String[]{"auth", "product"}) {
                        RedisSentinelConfiguration configuration = context.getBean(
                                group + "RedisConnectionFactory", LettuceConnectionFactory.class)
                                .getSentinelConfiguration();
                        assertThat(configuration).isNotNull();
                        assertThat(configuration.getMaster().getName()).isEqualTo("redis-" + group);
                        assertThat(configuration.getSentinelPassword().get())
                                .containsExactly((group + "-sentinel-test-password").toCharArray());
                    }
                });
    }

    @Test
    @DisplayName("인증 없는 Sentinel도 설정할 수 있으며 데이터 비밀번호를 재사용하지 않는다")
    void supportsSentinelWithoutPassword() {
        runner.withPropertyValues(sentinelSettings("auth"))
                .withPropertyValues("app.redis.auth.sentinel.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedisSentinelConfiguration configuration = context.getBean(
                            "authRedisConnectionFactory", LettuceConnectionFactory.class).getSentinelConfiguration();
                    assertThat(configuration.getSentinelPassword().isPresent()).isFalse();
                    assertThat(configuration.getPassword().isPresent()).isTrue();
                });
    }

    @Test
    @DisplayName("대괄호 IPv6 주소와 포트를 Sentinel 노드로 해석한다")
    void supportsBracketedIpv6SentinelAddress() {
        runner.withPropertyValues(sentinelSettings("auth"))
                .withPropertyValues("app.redis.auth.sentinel.nodes=[::1]:26379")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("authRedisConnectionFactory", LettuceConnectionFactory.class)
                            .getSentinelConfiguration().getSentinels()).containsExactly(new RedisNode("::1", 26379));
                });
    }

    @ParameterizedTest
    @MethodSource("invalidSentinelSettings")
    @DisplayName("Sentinel 필수 설정 누락이나 잘못된 주소는 고정 주소로 우회하지 않고 기동을 거부한다")
    void rejectsInvalidSentinelConfiguration(String group, String invalidSetting) {
        runner.withPropertyValues(sentinelSettings(group))
                .withPropertyValues("app.redis." + group + ".sentinel." + invalidSetting)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Redis Sentinel");
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("설정이 필요합니다.")
                            .satisfiesAnyOf(
                                    failure -> assertThat(failure).hasStackTraceContaining("master 설정"),
                                    failure -> assertThat(failure).hasStackTraceContaining("nodes 설정"));
                });
    }

    @ParameterizedTest
    @MethodSource("malformedSentinelNodes")
    @DisplayName("포트 누락·범위 오류·빈 항목·URL·인증 정보가 있는 Sentinel 주소를 거부한다")
    void rejectsMalformedSentinelNodesWithoutLeakingInput(String group, String nodes) {
        runner.withPropertyValues(sentinelSettings(group))
                .withPropertyValues("app.redis." + group + ".sentinel.nodes=" + nodes)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Redis Sentinel nodes는 쉼표로 구분한 host:port 형식이어야 합니다.");
                    assertThat(context.getStartupFailure().toString()).doesNotContain("do-not-log");
                    assertThat(context.getStartupFailure().getCause().getMessage()).doesNotContain("do-not-log");
                });
    }

    private static String[] sentinelSettings(String group) {
        String prefix = "app.redis." + group + ".sentinel.";
        return new String[]{
                prefix + "enabled=true",
                prefix + "master=redis-" + group,
                prefix + "nodes=sentinel-0.invalid:26379, sentinel-1.invalid:26379,sentinel-2.invalid:26379",
                prefix + "password=sentinel-test-password"
        };
    }

    private static Stream<Arguments> invalidSentinelSettings() {
        return Stream.of("auth", "product").flatMap(group ->
                Stream.of("master=", "nodes=").map(setting -> Arguments.of(group, setting)));
    }

    private static Stream<Arguments> malformedSentinelNodes() {
        return Stream.of("auth", "product").flatMap(group -> Stream.of(
                "sentinel.invalid", ":26379", "sentinel.invalid:0", "sentinel.invalid:65536",
                "sentinel.invalid:abc", "sentinel.invalid:26379,", "redis://sentinel.invalid:26379",
                "user:do-not-log@sentinel.invalid:26379", "sentinel.invalid:26379/path"
        ).map(nodes -> Arguments.of(group, nodes)));
    }
}
