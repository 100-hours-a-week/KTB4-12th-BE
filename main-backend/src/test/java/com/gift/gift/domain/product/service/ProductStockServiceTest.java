package com.gift.gift.domain.product.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.product.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductStockServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductStockService productStockService;

    @Test
    @DisplayName("재고 차감에 성공하면 true를 반환한다")
    void deductStockIfAvailable_returnsTrueWhenUpdated() {
        when(productRepository.deductStockIfAvailable(1L, 3)).thenReturn(1);

        assertThat(productStockService.deductStockIfAvailable(1L, 3))
                .isTrue();
    }

    @Test
    @DisplayName("변경된 행이 없으면 false를 반환한다")
    void deductStockIfAvailable_returnsFalseWhenNotUpdated() {
        when(productRepository.deductStockIfAvailable(1L, 3)).thenReturn(0);

        assertThat(productStockService.deductStockIfAvailable(1L, 3))
                .isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("상품 ID가 유효하지 않으면 DB를 호출하지 않는다")
    void deductStockIfAvailable_rejectsInvalidProductId(Long productId) {
        assertThatThrownBy(() ->
                productStockService.deductStockIfAvailable(productId, 1)
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(productRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("차감 수량이 양수가 아니면 DB를 호출하지 않는다")
    void deductStockIfAvailable_rejectsInvalidQuantity(int quantity) {
        assertThatThrownBy(() ->
                productStockService.deductStockIfAvailable(1L, quantity)
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(productRepository);
    }

    @Test
    @DisplayName("DB 장애를 재고 부족으로 처리하지 않고 예외를 전파한다")
    void deductStockIfAvailable_propagatesDatabaseFailure() {
        when(productRepository.deductStockIfAvailable(1L, 1))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));

        assertThatThrownBy(() ->
                productStockService.deductStockIfAvailable(1L, 1)
        ).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
