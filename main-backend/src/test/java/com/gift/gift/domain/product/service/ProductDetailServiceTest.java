package com.gift.gift.domain.product.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.product.dto.response.ProductDetailResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProductDetailServiceTest {

    @Test
    @DisplayName("상세를 조회한 뒤 조회 기록을 저장하고 응답을 반환한다")
    void getProductDetail_recordsOnlyAfterSuccessfulRead() {
        ProductQueryService query = mock(ProductQueryService.class);
        ProductViewService views = mock(ProductViewService.class);
        ProductDetailResponse response = mock(ProductDetailResponse.class);
        when(query.getProductDetail(2L)).thenReturn(response);

        assertThat(new ProductDetailService(query, views).getProductDetail(1L, 2L)).isSameAs(response);

        var order = inOrder(query, views);
        order.verify(query).getProductDetail(2L);
        order.verify(views).recordProductView(1L, 2L);
    }

    @Test
    @DisplayName("상세 조회가 실패하면 조회 기록을 호출하지 않는다")
    void getProductDetail_skipsRecordWhenReadFails() {
        ProductQueryService query = mock(ProductQueryService.class);
        ProductViewService views = mock(ProductViewService.class);
        IllegalStateException failure = new IllegalStateException("의도한 조회 실패");
        when(query.getProductDetail(2L)).thenThrow(failure);

        assertThatThrownBy(() -> new ProductDetailService(query, views).getProductDetail(1L, 2L))
                .isSameAs(failure);
        verifyNoInteractions(views);
    }
}
