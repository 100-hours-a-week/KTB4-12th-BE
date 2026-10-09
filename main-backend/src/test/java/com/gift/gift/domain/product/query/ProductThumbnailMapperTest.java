package com.gift.gift.domain.product.query;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.product.repository.ProductImageProjection;
import com.gift.gift.domain.product.support.ImageUrlProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductThumbnailMapperTest {

    private final ImageUrlProvider imageUrlProvider =
            objectKey -> "https://image.test/" + objectKey;

    private final ProductThumbnailMapper mapper =
            new ProductThumbnailMapper(imageUrlProvider);

    @Test
    @DisplayName("상품별 첫 번째 이미지를 대표 이미지 URL로 변환한다")
    void mapFirstImageForEachProduct() {
        List<Long> productIds = List.of(1L, 2L);

        List<ProductImageProjection> orderedImages = List.of(
                new ProductImageProjection(
                        1L,
                        "products/1/first.jpg"
                ),
                new ProductImageProjection(
                        1L,
                        "products/1/second.jpg"
                ),
                new ProductImageProjection(
                        2L,
                        "products/2/first.jpg"
                ),
                new ProductImageProjection(
                        2L,
                        "products/2/second.jpg"
                )
        );

        Map<Long, String> result =
                mapper.mapUrls(productIds, orderedImages);

        assertThat(result)
                .containsExactly(
                        Map.entry(
                                1L,
                                "https://image.test/products/1/first.jpg"
                        ),
                        Map.entry(
                                2L,
                                "https://image.test/products/2/first.jpg"
                        )
                );
    }

    @Test
    @DisplayName("이미지가 없는 상품은 null로 매핑한다")
    void mapNullWhenProductHasNoImage() {
        List<Long> productIds = List.of(1L, 2L);

        List<ProductImageProjection> orderedImages = List.of(
                new ProductImageProjection(
                        1L,
                        "products/1/main.jpg"
                )
        );

        Map<Long, String> result =
                mapper.mapUrls(productIds, orderedImages);

        assertThat(result.get(1L))
                .isEqualTo("https://image.test/products/1/main.jpg");
        assertThat(result).containsEntry(2L, null);
    }

    @Test
    @DisplayName("상품이 없으면 빈 결과를 반환한다")
    void returnEmptyMap() {
        Map<Long, String> result =
                mapper.mapUrls(List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("요청하지 않은 상품 이미지가 포함되면 거부한다")
    void rejectUnrequestedProductImage() {
        List<ProductImageProjection> orderedImages = List.of(
                new ProductImageProjection(
                        2L,
                        "products/2/main.jpg"
                )
        );

        assertThatThrownBy(() ->
                mapper.mapUrls(List.of(1L), orderedImages)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("요청하지 않은 상품 이미지가 포함되어 있습니다.");
    }

    @Test
    @DisplayName("반환된 대표 이미지 Map은 수정할 수 없다")
    void returnImmutableMap() {
        Map<Long, String> result = mapper.mapUrls(
                List.of(1L),
                List.of(
                        new ProductImageProjection(
                                1L,
                                "products/1/main.jpg"
                        )
                )
        );

        assertThatThrownBy(() ->
                result.put(2L, "https://image.test/other.jpg")
        ).isInstanceOf(UnsupportedOperationException.class);
    }
}
