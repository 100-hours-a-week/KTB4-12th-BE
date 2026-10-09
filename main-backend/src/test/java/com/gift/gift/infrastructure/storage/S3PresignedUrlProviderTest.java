package com.gift.gift.infrastructure.storage;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3PresignedUrlProviderTest {

    @Mock
    private S3Presigner presigner;

    @Mock
    private PresignedGetObjectRequest presignedRequest;

    private S3PresignedUrlProvider provider;

    @BeforeEach
    void setUp() {
        provider = new S3PresignedUrlProvider(
                presigner,
                "test-product-images"
        );
    }

    @Test
    @DisplayName("Object Key로 15분 유효한 S3 조회 URL을 생성한다")
    void generatePresignedGetUrl() throws MalformedURLException {
        URL expectedUrl = new URL(
                "https://test-product-images.s3.ap-northeast-2.amazonaws.com/"
                        + "products/1/main.jpg?signature=test"
        );

        when(presignedRequest.url()).thenReturn(expectedUrl);
        when(presigner.presignGetObject(
                org.mockito.ArgumentMatchers.any(
                        GetObjectPresignRequest.class
                )
        )).thenReturn(presignedRequest);

        String result = provider.generateUrl(
                "products/1/main.jpg"
        );

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(
                        GetObjectPresignRequest.class
                );

        verify(presigner).presignGetObject(captor.capture());

        GetObjectPresignRequest request = captor.getValue();

        assertThat(result).isEqualTo(expectedUrl.toExternalForm());
        assertThat(request.signatureDuration())
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(request.getObjectRequest().bucket())
                .isEqualTo("test-product-images");
        assertThat(request.getObjectRequest().key())
                .isEqualTo("products/1/main.jpg");
    }

    @Test
    @DisplayName("Object Key가 없으면 URL 생성을 거부한다")
    void rejectBlankObjectKey() {
        assertThatThrownBy(() -> provider.generateUrl(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미지 Object Key는 필수입니다.");
    }

    @Test
    @DisplayName("버킷 이름이 없으면 Provider 생성을 거부한다")
    void rejectBlankBucket() {
        assertThatThrownBy(() ->
                new S3PresignedUrlProvider(presigner, " ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미지 버킷 이름은 필수입니다.");
    }
}
