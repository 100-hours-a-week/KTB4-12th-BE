package com.gift.gift.infrastructure.storage;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import com.gift.gift.domain.product.support.ImageUrlProvider;

@Component
public class S3PresignedUrlProvider implements ImageUrlProvider {

    private static final Duration URL_DURATION = Duration.ofMinutes(15);

    private final S3Presigner presigner;
    private final String bucket;

    public S3PresignedUrlProvider(
            S3Presigner presigner,
            @Value("${storage.s3.bucket}") String bucket
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "이미지 버킷 이름은 필수입니다."
            );
        }

        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public String generateUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException(
                    "이미지 Object Key는 필수입니다."
            );
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(URL_DURATION)
                        .getObjectRequest(getObjectRequest)
                        .build();

        return presigner.presignGetObject(presignRequest)
                .url()
                .toExternalForm();
    }
}
