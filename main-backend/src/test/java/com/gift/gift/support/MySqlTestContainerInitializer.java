package com.gift.gift.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 테스트 JVM당 MySQL 컨테이너를 하나만 띄워 모든 Spring 테스트가 공유한다.
 * 컨테이너는 실행마다 새로 만들어지고 JVM 종료 시 폐기되므로 로컬/CI DB와 분리된다.
 * META-INF/spring.factories로 등록되어 기존 테스트 클래스를 수정하지 않아도 적용된다.
 */
public class MySqlTestContainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "spring.datasource.username=" + MYSQL.getUsername(),
                "spring.datasource.password=" + MYSQL.getPassword()
        ).applyTo(context);
    }
}
