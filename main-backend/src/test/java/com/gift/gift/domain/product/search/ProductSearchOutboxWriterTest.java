package com.gift.gift.domain.product.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProductSearchOutboxWriterTest {

    @Test
    @DisplayName("비활성 Writer는 DB에 접근하지 않는다")
    void disabledWriterDoesNotAccessDatabase() {
        ProductSearchOutboxRepository repository = mock(ProductSearchOutboxRepository.class);
        new ProductSearchOutboxWriter(repository, false).enqueue(1L);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("잘못된 상품 ID는 DB 호출 전에 거절한다")
    void invalidProductIdIsRejected() {
        ProductSearchOutboxRepository repository = mock(ProductSearchOutboxRepository.class);
        ProductSearchOutboxWriter writer = new ProductSearchOutboxWriter(repository, true);
        for (Long productId : new Long[]{null, 0L, -1L}) {
            assertThatThrownBy(() -> writer.enqueue(productId)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }
}
