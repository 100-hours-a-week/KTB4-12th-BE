package com.gift.gift.domain.product.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.gift.gift.domain.product.repository.ProductImageProjection;
import com.gift.gift.domain.product.support.ImageUrlProvider;

@Component
@RequiredArgsConstructor
public class ProductThumbnailMapper {

    private final ImageUrlProvider imageUrlProvider;

    public Map<Long, String> mapUrls(
            List<Long> productIds,
            List<ProductImageProjection> orderedImages
    ) {
        Objects.requireNonNull(productIds, "상품 ID 목록은 필수입니다.");
        Objects.requireNonNull(orderedImages, "이미지 조회 결과는 필수입니다.");

        Map<Long, String> firstObjectKeys = new LinkedHashMap<>();

        for (Long productId : productIds) {
            Objects.requireNonNull(productId, "상품 ID는 필수입니다.");
            firstObjectKeys.put(productId, null);
        }

        for (ProductImageProjection image : orderedImages) {
            if (!firstObjectKeys.containsKey(image.productId())) {
                throw new IllegalArgumentException(
                        "요청하지 않은 상품 이미지가 포함되어 있습니다."
                );
            }

            if (firstObjectKeys.get(image.productId()) == null) {
                firstObjectKeys.put(
                        image.productId(),
                        Objects.requireNonNull(
                                image.objectKey(),
                                "이미지 Object Key는 필수입니다."
                        )
                );
            }
        }

        Map<Long, String> urls = new LinkedHashMap<>();

        firstObjectKeys.forEach((productId, objectKey) ->
                urls.put(
                        productId,
                        objectKey == null
                                ? null
                                : imageUrlProvider.generateUrl(objectKey)
                )
        );

        return Collections.unmodifiableMap(urls);
    }
}
