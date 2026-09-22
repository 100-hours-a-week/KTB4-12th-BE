package com.gift.gift.domain.gift.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GiftHistoryTest {

    @Test
    @DisplayName("선물 이력은 필수 연관 객체가 없으면 생성할 수 없다")
    void giftHistory_rejectsNullRequiredAssociations() {
        User sender = createUser("sender@example.com", "보낸사람", LocalDate.of(1990, 1, 1));
        User recipient = createUser("recipient@example.com", "받는사람", LocalDate.of(1991, 1, 1));
        Product product = createProduct();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(null, recipient, product))
                .withMessage("보낸 사람, 받는 사람, 상품은 필수입니다.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(sender, null, product))
                .withMessage("보낸 사람, 받는 사람, 상품은 필수입니다.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(sender, recipient, null))
                .withMessage("보낸 사람, 받는 사람, 상품은 필수입니다.");
    }

    @Test
    @DisplayName("선물 이력은 필수 값이 없으면 생성할 수 없다")
    void giftHistory_rejectsNullRequiredValues() {
        User sender = createUser("sender@example.com", "보낸사람", LocalDate.of(1990, 1, 1));
        User recipient = createUser("recipient@example.com", "받는사람", LocalDate.of(1991, 1, 1));
        Product product = createProduct();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(
                        sender, recipient, product,
                        null,
                        BigDecimal.valueOf(10_000), "선물 상품",
                        UUID.randomUUID(), "a".repeat(64)
                ))
                .withMessage("수량, 가격 스냅샷, 상품명 스냅샷, 멱등성 키, 요청 지문은 필수입니다.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(
                        sender, recipient, product,
                        1,
                        null, "선물 상품",
                        UUID.randomUUID(), "a".repeat(64)
                ))
                .withMessage("수량, 가격 스냅샷, 상품명 스냅샷, 멱등성 키, 요청 지문은 필수입니다.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(
                        sender, recipient, product,
                        1,
                        BigDecimal.valueOf(10_000), null,
                        UUID.randomUUID(), "a".repeat(64)
                ))
                .withMessage("수량, 가격 스냅샷, 상품명 스냅샷, 멱등성 키, 요청 지문은 필수입니다.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(
                        sender, recipient, product,
                        1,
                        BigDecimal.valueOf(10_000), "선물 상품",
                        null, "a".repeat(64)
                ))
                .withMessage("수량, 가격 스냅샷, 상품명 스냅샷, 멱등성 키, 요청 지문은 필수입니다.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createGiftHistory(
                        sender, recipient, product,
                        1,
                        BigDecimal.valueOf(10_000), "선물 상품",
                        UUID.randomUUID(), null
                ))
                .withMessage("수량, 가격 스냅샷, 상품명 스냅샷, 멱등성 키, 요청 지문은 필수입니다.");
    }

    @Test
    @DisplayName("선물 이력은 발신자·수신자·상품 외래 키 이름을 명시한다")
    void giftHistory_declaresForeignKeyNames() throws NoSuchFieldException {
        assertThat(joinColumn("sender").foreignKey().name()).isEqualTo("fk_gift_histories_sender");
        assertThat(joinColumn("recipient").foreignKey().name()).isEqualTo("fk_gift_histories_recipient");
        assertThat(joinColumn("product").foreignKey().name()).isEqualTo("fk_gift_histories_product");
    }

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

    @Test
    @DisplayName("선물 이력은 보낸·받은 목록 커서 조회용 복합 인덱스를 선언한다")
    void giftHistory_declaresCursorQueryIndexes() {
        Table table = GiftHistory.class.getAnnotation(Table.class);

        assertThat(Arrays.stream(table.indexes()).map(Index::name))
                .containsExactly(
                        "idx_gift_histories_sender_completed",
                        "idx_gift_histories_recipient_completed"
                );
        assertThat(Arrays.stream(table.indexes()).map(Index::columnList))
                .containsExactly(
                        "sender_id, completed_at DESC, id DESC",
                        "recipient_id, completed_at DESC, id DESC"
                );
    }

    private User createUser(String email, String nickname, LocalDate birthDate) {
        return new User(email, "password", nickname, birthDate);
    }

    private Product createProduct() {
        Category rootCategory = new Category("대분류", null);
        Category leafCategory = new Category("소분류", rootCategory);
        return new Product(
                leafCategory,
                "선물 상품",
                "선물 브랜드",
                null,
                BigDecimal.valueOf(10_000),
                10
        );
    }

    private GiftHistory createGiftHistory(User sender, User recipient, Product product) {
        return createGiftHistory(
                sender,
                recipient,
                product,
                1,
                BigDecimal.valueOf(10_000),
                "선물 상품",
                UUID.randomUUID(),
                "a".repeat(64)
        );
    }

    private GiftHistory createGiftHistory(
            User sender,
            User recipient,
            Product product,
            Integer quantity,
            BigDecimal productPriceSnapshot,
            String productNameSnapshot,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        return new GiftHistory(
                sender,
                recipient,
                product,
                quantity,
                productPriceSnapshot,
                productNameSnapshot,
                idempotencyKey,
                requestFingerprint
        );
    }

    private JoinColumn joinColumn(String fieldName) throws NoSuchFieldException {
        return GiftHistory.class.getDeclaredField(fieldName).getAnnotation(JoinColumn.class);
    }
}
