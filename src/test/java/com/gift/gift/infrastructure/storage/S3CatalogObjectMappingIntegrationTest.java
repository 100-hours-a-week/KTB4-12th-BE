package com.gift.gift.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import static org.assertj.core.api.Assertions.assertThat;

class S3CatalogObjectMappingIntegrationTest {

    private static final String PREFIX = "test/products/";
    private static final int EXPECTED_OBJECT_COUNT = 4_205;
    private static final Pattern OBJECT_KEY_PATTERN = Pattern.compile(
            "'(?<key>test/products/[0-9a-f]+-768\\.avif)'"
    );

    @Test
    @DisplayName("V10의 모든 고유 상품 이미지 Object Key가 S3에 존재한다")
    @EnabledIfEnvironmentVariable(named = "AWS_REGION", matches = ".+")
    @EnabledIfEnvironmentVariable(
            named = "PRODUCT_IMAGE_BUCKET",
            matches = ".+"
    )
    void verifyCatalogObjectMappings() throws IOException {
        String region = System.getenv("AWS_REGION");
        String bucket = System.getenv("PRODUCT_IMAGE_BUCKET");
        Set<String> expectedKeys = readExpectedObjectKeys();

        assertThat(expectedKeys).hasSize(EXPECTED_OBJECT_COUNT);

        try (S3Client s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {
            Map<String, Long> uploadedObjects = new HashMap<>();

            s3Client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(PREFIX)
                            .build()
            ).contents().forEach(object ->
                    uploadedObjects.put(object.key(), object.size())
            );

            assertThat(uploadedObjects.keySet())
                    .containsAll(expectedKeys);
            expectedKeys.forEach(key ->
                    assertThat(uploadedObjects.get(key)).isPositive()
            );

            String firstKey = expectedKeys.stream().sorted().findFirst()
                    .orElseThrow();
            String lastKey = expectedKeys.stream().sorted().reduce((a, b) -> b)
                    .orElseThrow();

            assertAvifContentType(s3Client, bucket, firstKey);
            assertAvifContentType(s3Client, bucket, lastKey);
        }
    }

    private Set<String> readExpectedObjectKeys() throws IOException {
        String resourcePath =
                "db/seed/V10__seed_product_catalog.sql";

        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "V10 상품 카탈로그 seed를 찾을 수 없습니다."
                );
            }

            String sql = new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
            Matcher matcher = OBJECT_KEY_PATTERN.matcher(sql);
            Set<String> keys = new HashSet<>();

            while (matcher.find()) {
                keys.add(matcher.group("key"));
            }

            return keys;
        }
    }

    private void assertAvifContentType(
            S3Client s3Client,
            String bucket,
            String objectKey
    ) {
        String contentType = s3Client.headObject(
                HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()
        ).contentType();

        assertThat(contentType).isEqualTo("image/avif");
    }
}
