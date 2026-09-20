package com.gift.gift.domain.product.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.dto.response.ProductDetailResponse;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.query.ProductPageAssembler;
import com.gift.gift.domain.product.query.ProductThumbnailMapper;
import com.gift.gift.domain.product.repository.ProductImageRepository;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.support.ImageUrlProvider;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductDetailQueryServiceTest {

    private static final Long PRODUCT_ID = 101L;

    private ProductRepository productRepository;
    private ProductImageRepository productImageRepository;
    private ImageUrlProvider imageUrlProvider;
    private ProductQueryService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productImageRepository = mock(ProductImageRepository.class);
        imageUrlProvider = mock(ImageUrlProvider.class);

        service = new ProductQueryService(
                productRepository,
                productImageRepository,
                mock(ProductCursorCodec.class),
                mock(ProductPageAssembler.class),
                mock(ProductThumbnailMapper.class),
                imageUrlProvider
        );
    }

    @Test
    @DisplayName("상품 정보와 전체 이미지의 URL을 상세 응답으로 반환한다")
    void getProductDetail_returnsProductAndImages() {
        Product product = product("상품 설명", 8);
        ProductImage first = image(product, 1001L, "products/101/1.webp", 1);
        ProductImage second = image(product, 1002L, "products/101/2.webp", 2);

        stubDetail(product, List.of(first, second));

        when(imageUrlProvider.generateUrl(first.getObjectKey()))
                .thenReturn("https://image.test/1.webp");
        when(imageUrlProvider.generateUrl(second.getObjectKey()))
                .thenReturn("https://image.test/2.webp");

        ProductDetailResponse.ProductResponse result =
                service.getProductDetail(PRODUCT_ID).product();

        assertThat(result.productId()).isEqualTo(PRODUCT_ID);
        assertThat(result.brandName()).isEqualTo("테스트 브랜드");
        assertThat(result.productName()).isEqualTo("테스트 상품");
        assertThat(result.description()).isEqualTo("상품 설명");
        assertThat(result.unitPrice()).isEqualByComparingTo("32000");
        assertThat(result.stockQuantity()).isEqualTo(8);

        assertThat(result.images())
                .extracting(
                        item -> item.imageId(),
                        item -> item.imageUrl(),
                        item -> item.displayOrder()
                )
                .containsExactly(
                        tuple(1001L, "https://image.test/1.webp", 1),
                        tuple(1002L, "https://image.test/2.webp", 2)
                );

        verify(imageUrlProvider).generateUrl(first.getObjectKey());
        verify(imageUrlProvider).generateUrl(second.getObjectKey());
    }

    @Test
    @DisplayName("이미지가 없으면 빈 목록을 반환하고 URL을 생성하지 않는다")
    void getProductDetail_returnsEmptyImages() {
        stubDetail(product("상품 설명", 8), List.of());

        ProductDetailResponse response = service.getProductDetail(PRODUCT_ID);

        assertThat(response.product().images()).isEmpty();
        verifyNoInteractions(imageUrlProvider);
    }

    @Test
    @DisplayName("상품 설명이 없으면 null을 유지한다")
    void getProductDetail_preservesNullDescription() {
        stubDetail(product(null, 8), List.of());

        ProductDetailResponse response = service.getProductDetail(PRODUCT_ID);

        assertThat(response.product().description()).isNull();
    }

    @Test
    @DisplayName("품절 상품도 재고 0으로 정상 반환한다")
    void getProductDetail_returnsSoldOutProduct() {
        stubDetail(product("상품 설명", 0), List.of());

        ProductDetailResponse response = service.getProductDetail(PRODUCT_ID);

        assertThat(response.product().stockQuantity()).isZero();
    }

    @Test
    @DisplayName("이미지 표시 순서가 null이면 그대로 반환한다")
    void getProductDetail_preservesNullableDisplayOrder() {
        Product product = product("상품 설명", 8);
        ProductImage image = image(
                product, 1001L, "products/101/unordered.webp", null
        );

        stubDetail(product, List.of(image));
        when(imageUrlProvider.generateUrl(image.getObjectKey()))
                .thenReturn("https://image.test/unordered.webp");

        ProductDetailResponse response = service.getProductDetail(PRODUCT_ID);

        assertThat(response.product().images()).hasSize(1);
        assertThat(response.product().images().getFirst().displayOrder())
                .isNull();
    }

    @Test
    @DisplayName("상품이 없으면 상품 없음 예외를 발생시키고 이미지는 조회하지 않는다")
    void getProductDetail_throwsWhenProductNotFound() {
        when(productRepository.findByIdAndDeletedAtIsNull(PRODUCT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductDetail(PRODUCT_ID))
                .isInstanceOfSatisfying(
                        ProductException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
                            assertThat(exception.getMessage())
                                    .isEqualTo("상품을 찾을 수 없습니다.");
                        }
                );

        verifyNoInteractions(productImageRepository, imageUrlProvider);
    }

    private Product product(String description, int quantity) {
        Category root = new Category("대분류", null);
        Category child = new Category("세부 카테고리", root);

        Product product = new Product(
                child,
                "테스트 상품",
                "테스트 브랜드",
                description,
                new BigDecimal("32000"),
                quantity
        );
        ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
        return product;
    }

    private ProductImage image(
            Product product,
            Long imageId,
            String objectKey,
            Integer sortOrder
    ) {
        ProductImage image = new ProductImage(product, objectKey, sortOrder);
        ReflectionTestUtils.setField(image, "id", imageId);
        return image;
    }

    private void stubDetail(Product product, List<ProductImage> images) {
        when(productRepository.findByIdAndDeletedAtIsNull(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(productImageRepository.findActiveDetailImages(PRODUCT_ID))
                .thenReturn(images);
    }
}
