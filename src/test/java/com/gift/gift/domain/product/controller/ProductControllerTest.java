package com.gift.gift.domain.product.controller;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.dto.response.ProductSummaryResponse;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.global.pagination.CursorPageResponse.Pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductControllerTest {

    private ProductQueryService productQueryService;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        productQueryService = mock(ProductQueryService.class);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProductController(productQueryService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    @DisplayName("기본 상품 목록 조회 시 인기순과 공통 성공 응답을 반환한다")
    void getProducts_returnsDefaultProductList() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(
                        ProductSort.POPULAR,
                        "next-cursor",
                        true
                ));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("상품 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.products.length()")
                        .value(1))
                .andExpect(jsonPath("$.data.products[0].productId")
                        .value(101))
                .andExpect(jsonPath("$.data.products[0].brandName")
                        .value("테스트 브랜드"))
                .andExpect(jsonPath("$.data.products[0].productName")
                        .value("그린티 수분 크림"))
                .andExpect(jsonPath("$.data.products[0].price")
                        .value(32000))
                .andExpect(jsonPath("$.data.products[0].thumbnailUrl")
                        .value("https://image.test/products/101.jpg"))
                .andExpect(jsonPath("$.data.appliedSort")
                        .value("POPULAR"))
                .andExpect(jsonPath("$.data.pagination.nextCursor")
                        .value("next-cursor"))
                .andExpect(jsonPath("$.data.pagination.hasNext")
                        .value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        ProductListRequest request = captureRequest();

        assertThat(request.query()).isNull();
        assertThat(request.categoryIds()).isNull();
        assertThat(request.sort()).isNull();
        assertThat(request.recipientUserId()).isNull();
        assertThat(request.cursor()).isNull();
    }

    @Test
    @DisplayName("검색 조건을 상품 목록 요청으로 바인딩한다")
    void getProducts_bindsQuery() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(
                        ProductSort.POPULAR,
                        null,
                        false
                ));

        mockMvc.perform(get("/products")
                        .param("query", "우드향"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products.length()")
                        .value(1));

        ProductListRequest request = captureRequest();

        assertThat(request.query()).isEqualTo("우드향");
        assertThat(request.categoryIds()).isNull();
        assertThat(request.sort()).isNull();
    }

    @Test
    @DisplayName("여러 카테고리 ID를 상품 목록 요청으로 바인딩한다")
    void getProducts_bindsCategoryIds() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(
                        ProductSort.POPULAR,
                        null,
                        false
                ));

        mockMvc.perform(get("/products")
                        .param("categoryIds", "11,12"))
                .andExpect(status().isOk());

        ProductListRequest request = captureRequest();

        assertThat(request.categoryIds())
                .containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("단일 categoryIds 값을 카테고리 ID 목록으로 바인딩한다")
    void getProducts_bindsSingleCategoryId() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(ProductSort.POPULAR, null, false));

        mockMvc.perform(get("/products")
                        .param("categoryIds", "31"))
                .andExpect(status().isOk());

        assertThat(captureRequest().categoryIds()).containsExactly(31L);
    }

    @Test
    @DisplayName("반복된 categoryIds 값을 카테고리 ID 목록으로 바인딩한다")
    void getProducts_bindsRepeatedCategoryIds() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(ProductSort.POPULAR, null, false));

        mockMvc.perform(get("/products")
                        .param("categoryIds", "11", "12"))
                .andExpect(status().isOk());

        assertThat(captureRequest().categoryIds()).containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("단수 categoryId는 무시되어 카테고리 필터로 전달되지 않는다")
    void getProducts_ignoresSingularCategoryId() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(ProductSort.POPULAR, null, false));

        mockMvc.perform(get("/products")
                        .param("categoryId", "31,53"))
                .andExpect(status().isOk());

        // 성공 응답만으로 필터 적용을 판단하지 않고 Service 전달값을 확인한다.
        assertThat(captureRequest().categoryIds()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ProductSort.class)
    @DisplayName("허용된 정렬 조건을 상품 목록 요청에 적용한다")
    void getProducts_bindsSort(ProductSort sort) throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(
                        sort,
                        null,
                        false
                ));

        mockMvc.perform(get("/products")
                        .param("sort", sort.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSort")
                        .value(sort.name()));

        ProductListRequest request = captureRequest();

        assertThat(request.sort()).isEqualTo(sort);
    }

    @Test
    @DisplayName("요청 커서를 Service에 전달하고 다음 페이지 정보를 반환한다")
    void getProducts_returnsNextPage() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(
                        ProductSort.POPULAR,
                        "response-next-cursor",
                        true
                ));

        mockMvc.perform(get("/products")
                        .param("cursor", "request-cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagination.nextCursor")
                        .value("response-next-cursor"))
                .andExpect(jsonPath("$.data.pagination.hasNext")
                        .value(true));

        ProductListRequest request = captureRequest();

        assertThat(request.cursor()).isEqualTo("request-cursor");
    }

    @Test
    @DisplayName("마지막 페이지는 다음 커서 없이 반환한다")
    void getProducts_returnsLastPage() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(productListResponse(
                        ProductSort.NEWEST,
                        null,
                        false
                ));

        mockMvc.perform(get("/products")
                        .param("sort", "NEWEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.pagination.nextCursor",
                        nullValue()
                ))
                .andExpect(jsonPath("$.data.pagination.hasNext")
                        .value(false));
    }

    @Test
    @DisplayName("상품이 없으면 빈 목록과 빈 결과 메시지를 반환한다")
    void getProducts_returnsEmptyProductList() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenReturn(new ProductListResponse(
                        List.of(),
                        ProductSort.POPULAR,
                        new Pagination(null, false)
                ));

        mockMvc.perform(get("/products")
                        .param("query", "없는상품"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("일치하는 상품이 없습니다."))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.appliedSort")
                        .value("POPULAR"))
                .andExpect(jsonPath(
                        "$.data.pagination.nextCursor",
                        nullValue()
                ))
                .andExpect(jsonPath("$.data.pagination.hasNext")
                        .value(false))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "-1"
    })
    @DisplayName("0 이하의 카테고리 ID는 조회 조건 오류로 처리한다")
    void getProducts_rejectsNonPositiveCategoryId(
            String categoryId
    ) throws Exception {
        mockMvc.perform(get("/products")
                        .param("categoryIds", categoryId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("조회 조건을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(productQueryService);
    }

    @Test
    @DisplayName("숫자가 아닌 카테고리 ID는 조회 조건 오류로 처리한다")
    void getProducts_rejectsNonNumericCategoryId() throws Exception {
        mockMvc.perform(get("/products")
                        .param("categoryIds", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("조회 조건을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(productQueryService);
    }

    @Test
    @DisplayName("허용하지 않는 정렬값은 조회 조건 오류로 처리한다")
    void getProducts_rejectsUnknownSort() throws Exception {
        mockMvc.perform(get("/products")
                        .param("sort", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("조회 조건을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(productQueryService);
    }

    @Test
    @DisplayName("빈 정렬값은 INVALID_REQUEST로 처리한다")
    void getProducts_rejectsBlankSort() throws Exception {
        mockMvc.perform(get("/products")
                        .param("sort", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("조회 조건을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(productQueryService);
    }

    @Test
    @DisplayName("잘못된 커서는 공통 INVALID_CURSOR 응답으로 변환한다")
    void getProducts_returnsInvalidCursorError() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenThrow(new ProductException(
                        ErrorCode.INVALID_CURSOR
                ));

        mockMvc.perform(get("/products")
                        .param("cursor", "invalid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("페이지 정보를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }

    @Test
    @DisplayName("예상하지 못한 오류는 공통 서버 오류 형식으로 반환한다")
    void getProducts_returnsInternalServerError() throws Exception {
        when(productQueryService.getProducts(any()))
                .thenThrow(new IllegalStateException(
                        "테스트용 예상하지 못한 오류"
                ));

        mockMvc.perform(get("/products"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }

    @Test
    @DisplayName("수신자 기반 조회는 일반 상품 조회 Service로 전달하지 않는다")
    void getProducts_doesNotExecuteGeneralSearchForRecipient() throws Exception {
        mockMvc.perform(get("/products")
                        .param("recipientUserId", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("조회 조건을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .value(not(emptyOrNullString())));

        verifyNoInteractions(productQueryService);
    }

    private ProductListRequest captureRequest() {
        ArgumentCaptor<ProductListRequest> captor =
                ArgumentCaptor.forClass(ProductListRequest.class);

        verify(productQueryService)
                .getProducts(captor.capture());

        return captor.getValue();
    }

    private ProductListResponse productListResponse(
            ProductSort appliedSort,
            String nextCursor,
            boolean hasNext
    ) {
        ProductSummaryResponse product =
                new ProductSummaryResponse(
                        101L,
                        "테스트 브랜드",
                        "그린티 수분 크림",
                        new BigDecimal("32000"),
                        "https://image.test/products/101.jpg"
                );

        return new ProductListResponse(
                List.of(product),
                appliedSort,
                new Pagination(nextCursor, hasNext)
        );
    }
}
