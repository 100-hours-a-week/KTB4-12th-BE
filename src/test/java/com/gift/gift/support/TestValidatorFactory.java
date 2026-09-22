package com.gift.gift.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;

public final class TestValidatorFactory {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-18T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    private TestValidatorFactory() {
    }

    public static LocalValidatorFactoryBean create() {
        DefaultListableBeanFactory beanFactory =
                new DefaultListableBeanFactory();
        beanFactory.registerSingleton("clock", FIXED_CLOCK);

        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.setConstraintValidatorFactory(
                new SpringConstraintValidatorFactory(beanFactory)
        );
        validator.afterPropertiesSet();

        return validator;
    }
}
