package com.gift.gift.domain.product.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductDetailResponse;
import com.gift.gift.domain.product.dto.response.ProductImageResponse;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.dto.response.ProductSummaryResponse;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.query.ProductPage;
import com.gift.gift.domain.product.query.ProductPageAssembler;
import com.gift.gift.domain.product.query.ProductThumbnailMapper;
import com.gift.gift.domain.product.repository.*;
import com.gift.gift.domain.product.support.ImageUrlProvider;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductCursorCodec cursorCodec;
    private final ProductPageAssembler pageAssembler;
    private final ProductThumbnailMapper thumbnailMapper;
    private final ImageUrlProvider imageUrlProvider;

    public ProductListResponse getProducts(ProductListRequest request) {
        Objects.requireNonNull(
                request,
                "상품 조회 요청은 필수입니다."
        );

        // 1. 실제 적용할 일반 정렬 결정
        ProductSort appliedSort = resolveSort(request);

        // 2. 검색어·카테고리 정규화
        ProductSearchCondition condition = new ProductSearchCondition(
                request.query(),
                request.categoryIds(),
                appliedSort
        );

        // 3. 요청 커서 해석 및 현재 조회 조건과 일치 여부 검증
        ProductCursor cursor = cursorCodec.decode(
                request.cursor(),
                condition
        );

        // 4. 다음 페이지 판단을 위해 최대 21건 조회
        List<ProductSummaryProjection> fetched =
                productRepository.searchProducts(
                        condition,
                        cursor,
                        ProductPageAssembler.FETCH_COUNT
                );

        // 5. 최대 20건 추출, hasNext 판단, nextCursor 생성
        ProductPage page = pageAssembler.assemble(
                fetched,
                condition
        );

        // 6. 실제 응답하는 상품 ID만 수집
        List<Long> productIds = page.items().stream()
                .map(ProductSummaryProjection::productId)
                .toList();

        // 7. 대표 이미지 후보 일괄 조회
        List<ProductImageProjection> images = productIds.isEmpty()
                ? List.of()
                : productImageRepository.findThumbnailCandidates(
                productIds
        );

        // 8. 대표 이미지 선택 및 URL 조합
        Map<Long, String> thumbnailUrls = thumbnailMapper.mapUrls(
                productIds,
                images
        );

        // 9. 상품 요약 Response 생성
        List<ProductSummaryResponse> products = page.items().stream()
                .map(product -> ProductSummaryResponse.from(
                        product,
                        thumbnailUrls.get(product.productId())
                ))
                .toList();

        // 10. 목록·정렬·페이지 정보 조합
        return ProductListResponse.from(
                products,
                appliedSort,
                page
        );
    }

    public ProductDetailResponse getProductDetail(Long productId) {
        Product product = productRepository
                .findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(
                        () -> new ProductException(ErrorCode.PRODUCT_NOT_FOUND)
                );

        List<ProductImage> activeImages = productImageRepository
                .findActiveDetailImages(productId);

        List<ProductImageResponse> images = new ArrayList<>();
        for (ProductImage image : activeImages) {
            String imageUrl = imageUrlProvider.generateUrl(image.getObjectKey());
            images.add(ProductImageResponse.from(image, imageUrl));
        }

        return ProductDetailResponse.from(product, images);
    }

    private ProductSort resolveSort(ProductListRequest request) {
        if (request.sort() != null) {
            return request.sort();
        }

        if (request.recipientUserId() != null) {
            throw new UnsupportedOperationException(
                    "AI 추천 상품 조회는 후속 연동 범위입니다."
            );
        }

        return ProductSort.POPULAR;
    }
}
