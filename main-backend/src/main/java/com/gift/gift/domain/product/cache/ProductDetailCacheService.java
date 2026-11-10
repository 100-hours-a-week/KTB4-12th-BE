package com.gift.gift.domain.product.cache;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;

import com.gift.gift.domain.product.dto.response.ProductDetailResponse;
import com.gift.gift.domain.product.dto.response.ProductImageResponse;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductDetailProjection;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.support.ImageUrlProvider;
import com.gift.gift.global.exception.ErrorCode;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDetailCacheService {

    private final ProductRepository productRepository;
    private final ProductDetailCacheRepository cacheRepository;
    private final ImageUrlProvider imageUrlProvider;
    private final ProductDetailCacheMetrics metrics;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductDetailResponse getProductDetail(Long productId) {
        Integer stockQuantity = metrics.time(
                        ProductDetailCacheMetrics.Stage.STOCK_DB,
                        () -> productRepository.findActiveStockQuantityById(productId))
                .orElseThrow(
                        () -> new ProductException(ErrorCode.PRODUCT_NOT_FOUND)
                );

        ProductDetailCache cache = findOrLoad(productId);

        return metrics.time(ProductDetailCacheMetrics.Stage.RESPONSE_BUILD, () -> {
            List<ProductImageResponse> images = cache.images().stream()
                    .map(image -> new ProductImageResponse(
                            image.imageId(),
                            imageUrlProvider.generateUrl(image.objectKey()),
                            image.displayOrder()
                    ))
                    .toList();

            return ProductDetailResponse.from(
                    cache,
                    stockQuantity,
                    images
            );
        });
    }

    private ProductDetailCache findOrLoad(Long productId) {
        Optional<ProductDetailCache> cached;

        try {
            cached = metrics.time(ProductDetailCacheMetrics.Stage.CACHE_READ,
                    () -> cacheRepository.findById(productId));
        } catch (DataAccessException exception) {
            metrics.read("error");
            log.warn(
                    "상품 캐시 조회 실패. DB로 조회합니다. productId={}",
                    productId,
                    exception
            );

            // 조회가 실패한 Redis에 다시 저장을 시도하며 기다리지 않는다.
            return loadFromDatabase(productId);
        }

        if (cached.isPresent()) {
            metrics.read("hit");
            return cached.get();
        }

        metrics.read("miss");

        ProductDetailCache loaded = loadFromDatabase(productId);

        try {
            metrics.time(ProductDetailCacheMetrics.Stage.CACHE_WRITE, () -> {
                cacheRepository.save(loaded);
                return null;
            });
            metrics.write("success");
        } catch (DataAccessException | JacksonException
                 | IllegalStateException exception) {
            metrics.write("error");
            log.warn(
                    "상품 캐시 저장 실패. DB 조회 결과를 반환합니다. productId={}",
                    productId,
                    exception
            );
        }

        return loaded;
    }

    private ProductDetailCache loadFromDatabase(Long productId) {
        return metrics.time(ProductDetailCacheMetrics.Stage.METADATA_DB,
                () -> readMetadataFromDatabase(productId));
    }

    private ProductDetailCache readMetadataFromDatabase(Long productId) {
        List<ProductDetailProjection> rows =
                productRepository.findActiveDetail(productId);

        if (rows.isEmpty()) {
            throw new ProductException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        ProductDetailProjection product = rows.getFirst();

        List<ProductDetailCache.ImageMetadata> images = rows.stream()
                .filter(row -> row.imageId() != null)
                .map(row -> new ProductDetailCache.ImageMetadata(
                        row.imageId(),
                        row.objectKey(),
                        row.sortOrder()
                ))
                .toList();

        return new ProductDetailCache(
                product.productId(),
                product.brand(),
                product.name(),
                product.description(),
                product.price(),
                images
        );
    }
}
