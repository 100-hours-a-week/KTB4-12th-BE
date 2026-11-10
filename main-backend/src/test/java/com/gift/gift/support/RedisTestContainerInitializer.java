package com.gift.gift.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;

public class RedisTestContainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        for (String endpoint : new String[]{"auth", "product"}) {
            String prefix = "app.redis." + endpoint + ".";
            TestPropertyValues.of(
                    prefix + "host=" + REDIS.getHost(),
                    prefix + "port=" + REDIS.getMappedPort(6379),
                    prefix + "password=",
                    prefix + "connect-timeout=1s",
                    prefix + "command-timeout=1s"
            ).applyTo(context);
        }
    }
}
