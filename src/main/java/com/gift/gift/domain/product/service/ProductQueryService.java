package com.gift.gift.domain.product.service;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductDetailResponse;
import com.gift.gift.domain.product.dto.response.ProductImageResponse;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.dto.response.ProductSummaryResponse;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;
import com.gift.gift.domain.product.exception.ProductErrorCode;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.query.ProductPage;
import com.gift.gift.domain.product.query.ProductPageAssembler;
import com.gift.gift.domain.product.query.ProductThumbnailMapper;
import com.gift.gift.domain.product.repository.*;
import com.gift.gift.domain.product.support.ImageUrlProvider;
import com.gift.gift.domain.user.service.UserQueryService;
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
    private final UserQueryService userQueryService;
    private final FriendQueryService friendQueryService;

    public ProductListResponse getProducts(
            ProductListRequest request,
            Long loginUserId
    ) {
        Objects.requireNonNull(
                request,
                "상품 조회 요청은 필수입니다."
        );

        ProductSort requestedSort = resolveRequestedSort(request);

        validateRecipientAccess(
                request.recipientUserId(),
                loginUserId
        );

        // 저장된 추천 결과에 따라 실제 정렬을 결정한다.
        ProductSort appliedSort =
                requestedSort == ProductSort.AI_RECOMMENDED
                        ? ProductSort.POPULAR
                        : requestedSort;

        ProductSearchCondition condition = new ProductSearchCondition(
                request.query(),
                request.categoryIds(),
                appliedSort
        );

        ProductCursor cursor = cursorCodec.decode(
                request.cursor(),
                condition
        );

        List<ProductSummaryProjection> fetched =
                productRepository.searchProducts(
                        condition,
                        cursor,
                        ProductPageAssembler.FETCH_COUNT
                );

        ProductPage page = pageAssembler.assemble(
                fetched,
                condition
        );

        List<Long> productIds = page.items().stream()
                .map(ProductSummaryProjection::productId)
                .toList();

        Map<Long, String> thumbnailUrls = findThumbnailUrls(productIds);

        List<ProductSummaryResponse> products = page.items().stream()
                .map(product -> ProductSummaryResponse.from(
                        product,
                        thumbnailUrls.get(product.productId())
                ))
                .toList();

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

    public Optional<Product> findAvailableProduct(Long productId) {
        return productRepository.findByIdAndDeletedAtIsNull(productId);
    }

    public Map<Long, String> findThumbnailUrls(List<Long> productIds) {
        List<ProductImageProjection> images = productIds.isEmpty()
                ? List.of()
                : productImageRepository.findThumbnailCandidates(productIds);

        return thumbnailMapper.mapUrls(productIds, images);
    }

    private ProductSort resolveRequestedSort(
            ProductListRequest request
    ) {
        Long recipientUserId = request.recipientUserId();

        if (recipientUserId != null && recipientUserId <= 0) {
            throw new ProductException(
                    ProductErrorCode.INVALID_REQUEST
            );
        }

        if (request.sort() == ProductSort.AI_RECOMMENDED && recipientUserId == null) {
            throw new ProductException(
                    ProductErrorCode.INVALID_REQUEST
            );
        }

        if (request.sort() != null) {
            return request.sort();
        }

        return recipientUserId == null ? ProductSort.POPULAR : ProductSort.AI_RECOMMENDED;
    }

    private void validateRecipientAccess(
            Long recipientUserId,
            Long loginUserId
    ) {
        if (recipientUserId == null) {
            return;
        }

        if (loginUserId == null) {
            throw new ProductException(
                    ErrorCode.UNAUTHORIZED
            );
        }

        if (userQueryService.findActiveUser(recipientUserId).isEmpty()) {
            throw new ProductException(
                    ProductErrorCode.RECIPIENT_NOT_FOUND
            );
        }

        if (!friendQueryService.areFriends(
                loginUserId,
                recipientUserId
        )) {
            throw new ProductException(
                    ProductErrorCode.RECIPIENT_NOT_FOUND
            );
        }
    }
}
