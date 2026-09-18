package com.gift.gift.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3StorageConfig {

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(
            @Value("${storage.s3.region}") String region
    ) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }
}
