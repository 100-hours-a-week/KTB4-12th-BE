package com.gift.gift.domain.gift.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.annotations.ColumnDefault;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

class GiftHistoryTest {

    @Test
    @DisplayName("선물 이력은 생성 즉시 완료 상태와 완료 시각을 가진다")
    void giftHistory_isCompleted_whenCreated() {
        User sender = new User("sender@example.com", "password", "보낸사람", LocalDate.of(1990, 1, 1));
        User recipient = new User("recipient@example.com", "password", "받는사람", LocalDate.of(1991, 1, 1));
        Category rootCategory = new Category("대분류", null);
        Category leafCategory = new Category("소분류", rootCategory);
        Product product = new Product(
                leafCategory,
                "선물 상품",
                "선물 브랜드",
                null,
                BigDecimal.valueOf(10_000),
                10
        );
        UUID idempotencyKey = UUID.randomUUID();
        LocalDateTime beforeCreation = LocalDateTime.now();

        GiftHistory giftHistory = new GiftHistory(
                sender,
                recipient,
                product,
                2,
                BigDecimal.valueOf(10_000),
                "선물 상품",
                idempotencyKey,
                "a".repeat(64)
        );

        assertThat(giftHistory.getSender()).isSameAs(sender);
        assertThat(giftHistory.getRecipient()).isSameAs(recipient);
        assertThat(giftHistory.getProduct()).isSameAs(product);
        assertThat(giftHistory.getStatus()).isEqualTo(GiftStatus.COMPLETED);
        assertThat(giftHistory.getCompletedAt()).isAfterOrEqualTo(beforeCreation);
        assertThat(giftHistory.getIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    @DisplayName("선물 이력은 ERD의 유일성·체크·기본값 제약을 선언한다")
    void giftHistory_declaresDatabaseConstraints() throws NoSuchFieldException {
        Table table = GiftHistory.class.getAnnotation(Table.class);

        assertThat(table.uniqueConstraints())
                .extracting(uniqueConstraint -> uniqueConstraint.name())
                .containsExactly("uk_gift_histories_sender_idempotency");
        assertThat(Arrays.stream(table.check()).map(CheckConstraint::constraint))
                .containsExactlyInAnyOrder(
                        "sender_id <> recipient_id",
                        "quantity >= 1",
                        "product_price_snapshot >= 0",
                        "status IN ('PROCESSING', 'COMPLETED', 'FAILED')"
                );
        assertThat(GiftHistory.class.getDeclaredField("status").getAnnotation(ColumnDefault.class).value())
                .isEqualTo("'COMPLETED'");
    }
}
