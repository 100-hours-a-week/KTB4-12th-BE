package com.gift.gift.global.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitManagementConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ManagementConsumer.class)
            .withPropertyValues("app.rabbitmq.management.url=http://broker.invalid:15672",
                    "app.rabbitmq.management.username=test", "app.rabbitmq.management.password=test-password");

    @Test
    @DisplayName("공통 구성을 가져온 기능은 기동 시 외부 접속 없이 관리 클라이언트를 생성한다")
    void importsManagementConfigurationWithoutConnecting() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasBean("rabbitManagementRestClient");
            assertThat(context.getBean(RabbitManagementProperties.class).url().toString())
                    .isEqualTo("http://broker.invalid:15672");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.rabbitmq.management.url=", "app.rabbitmq.management.username=",
            "app.rabbitmq.management.password=", "app.rabbitmq.management.url=ftp://broker.invalid",
            "app.rabbitmq.management.url=http://user:secret@broker.invalid"})
    @DisplayName("관리 클라이언트 사용 시 필수 접속 정보와 안전한 URL을 검증한다")
    void rejectsInvalidManagementSettings(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RabbitManagementConfiguration.class)
    static class ManagementConsumer {
    }
}
