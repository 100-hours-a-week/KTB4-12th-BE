package com.gift.gift.domain.product.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.dto.response.ProductSummaryResponse;
import com.gift.gift.domain.product.query.ProductPageAssembler;
import com.gift.gift.domain.product.query.ProductThumbnailMapper;
import com.gift.gift.domain.product.repository.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductQueryServiceTest {

    private ProductRepository productRepository;
    private ProductImageRepository productImageRepository;
    private ProductCursorCodec cursorCodec;
    private ProductQueryService productQueryService;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productImageRepository = mock(ProductImageRepository.class);

        cursorCodec = new ProductCursorCodec(
                JsonMapper.builder().build()
        );

        ProductPageAssembler pageAssembler =
                new ProductPageAssembler(cursorCodec);

        ProductThumbnailMapper thumbnailMapper =
                new ProductThumbnailMapper(
                        objectKey -> "https://image.test/" + objectKey
                );

        productQueryService = new ProductQueryService(
                productRepository,
                productImageRepository,
                cursorCodec,
                pageAssembler,
                thumbnailMapper,
                objectKey -> "https://image/test/" + objectKey
        );
    }

    // 1. 기본 정렬 결정 테스트
    @Test
    @DisplayName("수신자와 정렬이 없으면 인기순으로 조회한다")
    void getProducts_defaultsToPopular() {
        stubFirstPage(List.of());

        ProductListResponse response =
                productQueryService.getProducts(defaultRequest());

        ProductSearchCondition condition = captureCondition();

        assertThat(condition.sort()).isEqualTo(ProductSort.POPULAR);
        assertThat(condition.query()).isEmpty();
        assertThat(condition.categoryIds()).isEmpty();

        assertThat(response.appliedSort())
                .isEqualTo(ProductSort.POPULAR);
    }

    // 2. 요청 정렬 적용 테스트
    @ParameterizedTest
    @EnumSource(ProductSort.class)
    @DisplayName("요청한 정렬을 조회 조건과 응답에 적용한다")
    void getProducts_appliesRequestedSort(ProductSort sort) {
        stubFirstPage(List.of());

        ProductListRequest request = new ProductListRequest(
                null,
                null,
                sort,
                null,
                null
        );

        ProductListResponse response =
                productQueryService.getProducts(request);

        ProductSearchCondition condition = captureCondition();

        assertThat(condition.sort()).isEqualTo(sort);
        assertThat(response.appliedSort()).isEqualTo(sort);
    }

    // 3. 검색 조건 변환 테스트
    @Test
    @DisplayName("검색어의 앞뒤 공백을 제거하여 조회 조건으로 전달한다")
    void getProducts_normalizesQuery() {
        stubFirstPage(List.of());

        ProductListRequest request = new ProductListRequest(
                "  그린티 크림  ",
                null,
                null,
                null,
                null
        );

        productQueryService.getProducts(request);

        ProductSearchCondition condition = captureCondition();

        assertThat(condition.query()).isEqualTo("그린티 크림");
        assertThat(condition.hasQuery()).isTrue();
    }

    // 4. 카테고리 조건 변환 테스트
    @Test
    @DisplayName("카테고리 ID의 중복을 제거하고 정렬하여 전달한다")
    void getProducts_normalizesCategoryIds() {
        stubFirstPage(List.of());

        ProductListRequest request = new ProductListRequest(
                null,
                List.of(12L, 11L, 12L, 13L),
                null,
                null,
                null
        );

        productQueryService.getProducts(request);

        ProductSearchCondition condition = captureCondition();

        assertThat(condition.categoryIds())
                .containsExactly(11L, 12L, 13L);

        assertThat(condition.hasCategoryIds()).isTrue();
    }

    // 5. 최대 20건 응답 테스트
    @Test
    @DisplayName("21건을 조회해도 앞의 20건만 응답하고 이미지를 조회한다")
    void getProducts_returnsAtMostTwentyProducts() {
        List<ProductSummaryProjection> fetched = products(21);
        stubFirstPage(fetched);

        List<Long> responseProductIds = fetched.subList(0, 20).stream()
                .map(ProductSummaryProjection::productId)
                .toList();

        when(productImageRepository.findThumbnailCandidates(
                responseProductIds
        )).thenReturn(List.of());

        ProductListResponse response =
                productQueryService.getProducts(defaultRequest());

        assertThat(response.products()).hasSize(20);

        assertThat(response.products())
                .extracting(ProductSummaryResponse::productId)
                .containsExactlyElementsOf(responseProductIds);

        // 상품 조회 요청 개수가 21인지 확인한다.
        captureCondition();

        // 21번째 상품은 이미지 조회 대상에서도 제외한다.
        verify(productImageRepository)
                .findThumbnailCandidates(responseProductIds);

        verifyNoMoreInteractions(productImageRepository);
    }

    // 6. 다음 페이지 판단 테스트
    @Test
    @DisplayName("21건이 조회되면 다음 페이지가 있다고 응답한다")
    void getProducts_hasNextWhenTwentyOneProductsFetched() {
        stubFirstPage(products(21));

        ProductListResponse response =
                productQueryService.getProducts(defaultRequest());

        assertThat(response.pagination().hasNext()).isTrue();
        assertThat(response.pagination().nextCursor()).isNotBlank();
    }

    // 7. 마지막 페이지 처리 테스트
    @ParameterizedTest
    @ValueSource(ints = {1, 19, 20})
    @DisplayName("20건 이하이면 마지막 페이지로 처리한다")
    void getProducts_returnsLastPage(int count) {
        stubFirstPage(products(count));

        ProductListResponse response =
                productQueryService.getProducts(defaultRequest());

        assertThat(response.products()).hasSize(count);
        assertThat(response.pagination().hasNext()).isFalse();
        assertThat(response.pagination().nextCursor()).isNull();
    }

    // 8. 빈 결과 처리 테스트
    @Test
    @DisplayName("검색 결과가 없으면 빈 목록을 반환하고 이미지 조회를 생략한다")
    void getProducts_returnsEmptyResult() {
        stubFirstPage(List.of());

        ProductListResponse response =
                productQueryService.getProducts(defaultRequest());

        assertThat(response.products()).isEmpty();
        assertThat(response.appliedSort())
                .isEqualTo(ProductSort.POPULAR);

        assertThat(response.pagination().hasNext()).isFalse();
        assertThat(response.pagination().nextCursor()).isNull();

        verifyNoInteractions(productImageRepository);
    }

    // 9. 대표 이미지가 없는 상품 처리 테스트
    @Test
    @DisplayName("이미지가 없는 상품도 반환하며 thumbnailUrl은 null이다")
    void getProducts_returnsNullThumbnailWhenImageMissing() {
        List<ProductSummaryProjection> fetched = products(2);
        stubFirstPage(fetched);

        Long firstProductId = fetched.get(0).productId();
        Long secondProductId = fetched.get(1).productId();

        when(productImageRepository.findThumbnailCandidates(
                List.of(firstProductId, secondProductId)
        )).thenReturn(List.of(
                new ProductImageProjection(
                        firstProductId,
                        "products/100/main.jpg"
                )
        ));

        ProductListResponse response =
                productQueryService.getProducts(defaultRequest());

        assertThat(response.products()).hasSize(2);

        ProductSummaryResponse first = response.products().get(0);
        ProductSummaryResponse second = response.products().get(1);

        assertThat(first.productId()).isEqualTo(firstProductId);
        assertThat(first.thumbnailUrl())
                .isEqualTo(
                        "https://image.test/products/100/main.jpg"
                );

        assertThat(second.productId()).isEqualTo(secondProductId);
        assertThat(second.thumbnailUrl()).isNull();

        // 이미지가 없어도 상품의 나머지 정보는 유지한다.
        assertThat(second.productName())
                .isEqualTo(fetched.get(1).productName());

        assertThat(second.brandName())
                .isEqualTo(fetched.get(1).brandName());

        assertThat(second.price())
                .isEqualByComparingTo(fetched.get(1).price());
    }

    // 10. 다음 커서 생성 테스트
    @ParameterizedTest
    @EnumSource(ProductSort.class)
    @DisplayName("다음 커서는 20번째 응답 상품과 정규화된 조회 조건으로 생성한다")
    void getProducts_createsCursorFromLastReturnedProduct(
            ProductSort sort
    ) {
        List<ProductSummaryProjection> fetched = products(21);
        stubFirstPage(fetched);

        ProductListRequest request = new ProductListRequest(
                "  크림  ",
                List.of(12L, 11L, 12L),
                sort,
                null,
                null
        );

        ProductListResponse response =
                productQueryService.getProducts(request);

        assertThat(response.pagination().nextCursor()).isNotBlank();

        ProductSearchCondition condition = captureCondition();

        ProductCursor decoded = cursorCodec.decode(
                response.pagination().nextCursor(),
                condition
        );

        // 21번째 조회 상품이 아니라 20번째 응답 상품이 기준이다.
        ProductSummaryProjection lastReturned = fetched.get(19);
        ProductSummaryProjection extraProduct = fetched.get(20);

        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.sort()).isEqualTo(sort);
        assertThat(decoded.query()).isEqualTo("크림");

        assertThat(decoded.categoryIds())
                .containsExactly(11L, 12L);

        assertThat(decoded.productId())
                .isEqualTo(lastReturned.productId())
                .isNotEqualTo(extraProduct.productId());

        assertThat(decoded.createdAt())
                .isEqualTo(lastReturned.createdAt());

        if (sort == ProductSort.NEWEST) {
            assertThat(decoded.views()).isNull();
            assertThat(decoded.sales()).isNull();
        } else {
            assertThat(decoded.views())
                    .isEqualTo(lastReturned.views());

            assertThat(decoded.sales())
                    .isEqualTo(lastReturned.sales());
        }
    }

    private ProductListRequest defaultRequest() {
        return new ProductListRequest(
                null,
                null,
                null,
                null,
                null
        );
    }

    private void stubFirstPage(
            List<ProductSummaryProjection> fetched
    ) {
        when(productRepository.searchProducts(
                any(ProductSearchCondition.class),
                isNull(),
                eq(21)
        )).thenReturn(fetched);
    }

    private ProductSearchCondition captureCondition() {
        ArgumentCaptor<ProductSearchCondition> captor =
                ArgumentCaptor.forClass(ProductSearchCondition.class);

        verify(productRepository).searchProducts(
                captor.capture(),
                isNull(),
                eq(21)
        );

        return captor.getValue();
    }

    private List<ProductSummaryProjection> products(int count) {
        LocalDateTime baseTime = LocalDateTime.of(2026, 9, 18, 12, 0);
        List<ProductSummaryProjection> list = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            long productId = 100L - index;

            ProductSummaryProjection projection = new ProductSummaryProjection(
                    productId,
                    "크림 " + productId,
                    "테스트 브랜드",
                    new BigDecimal("32000"),
                    100 - index,
                    50 - index,
                    baseTime.minusMinutes(index)
            );

            list.add(projection);
        }

        return List.copyOf(list); // 읽기 전용 리스트로 반환
    }
}
