package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.gift.support.GiftRequestFingerprintGenerator;
import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.service.PreferenceQueryService;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GiftServiceTest {

    private GiftHistoryRepository giftHistoryRepository;
    private UserQueryService userQueryService;
    private FriendQueryService friendQueryService;
    private ProductQueryService productQueryService;
    private PreferenceQueryService preferenceQueryService;
    private GiftCommandService giftCommandService;
    private GiftRequestFingerprintGenerator fingerprintGenerator;
    private GiftService giftService;

    @BeforeEach
    void setUp() {
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        userQueryService = mock(UserQueryService.class);
        friendQueryService = mock(FriendQueryService.class);
        productQueryService = mock(ProductQueryService.class);
        preferenceQueryService = mock(PreferenceQueryService.class);
        giftCommandService = mock(GiftCommandService.class);
        fingerprintGenerator = mock(GiftRequestFingerprintGenerator.class);
        giftService = new GiftService(
                giftHistoryRepository,
                userQueryService,
                friendQueryService,
                productQueryService,
                preferenceQueryService,
                giftCommandService,
                fingerprintGenerator
        );
    }

    @Test
    @DisplayName("사전 검증 성공 시 수신자·상품·가격·최대 주문 수량을 반환한다")
    void preflight_returnsCalculatedResponse_whenConditionsAreValid() {
        Long senderId = 1L;
        Long recipientId = 2L;
        Long productId = 3L;
        Long categoryId = 9L;
        Product product = mock(Product.class);
        Category category = mock(Category.class);
        GiftPreflightRequest request = new GiftPreflightRequest(productId, recipientId, 2);

        when(userQueryService.findActiveUser(recipientId))
                .thenReturn(Optional.of(new ActiveUserSummary(recipientId, "수신자")));
        when(friendQueryService.areFriends(senderId, recipientId)).thenReturn(true);
        when(productQueryService.findAvailableProduct(productId)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(product.getQuantity()).thenReturn(15);
        when(product.getCategory()).thenReturn(category);
        when(category.getId()).thenReturn(categoryId);
        when(preferenceQueryService.findMatchingWarning(recipientId, categoryId))
                .thenReturn(Optional.empty());

        GiftPreflightResponse response = giftService.preflight(senderId, request);

        assertThat(response.recipient())
                .isEqualTo(new GiftPreflightResponse.Recipient(recipientId, "수신자"));
        assertThat(response.product()).isEqualTo(new GiftPreflightResponse.Product(
                productId,
                BigDecimal.valueOf(32_000),
                2,
                BigDecimal.valueOf(64_000),
                10
        ));
        assertThat(response.preferenceWarning()).isNull();

        var order = inOrder(userQueryService, friendQueryService, productQueryService, preferenceQueryService);
        order.verify(userQueryService).findActiveUser(recipientId);
        order.verify(friendQueryService).areFriends(senderId, recipientId);
        order.verify(productQueryService).findAvailableProduct(productId);
        order.verify(preferenceQueryService).findMatchingWarning(recipientId, categoryId);
    }

    @Test
    @DisplayName("수신자가 상품 카테고리를 비선호로 등록했으면 경고 정보를 포함한다")
    void preflight_includesPreferenceWarning_whenRecipientDislikesProductCategory() {
        Long senderId = 1L;
        Long recipientId = 2L;
        Long productId = 3L;
        Long categoryId = 9L;
        Product product = mock(Product.class);
        Category category = mock(Category.class);
        GiftPreflightRequest request = new GiftPreflightRequest(productId, recipientId, 2);

        when(userQueryService.findActiveUser(recipientId))
                .thenReturn(Optional.of(new ActiveUserSummary(recipientId, "수신자")));
        when(friendQueryService.areFriends(senderId, recipientId)).thenReturn(true);
        when(productQueryService.findAvailableProduct(productId)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(product.getQuantity()).thenReturn(15);
        when(product.getCategory()).thenReturn(category);
        when(category.getId()).thenReturn(categoryId);
        when(preferenceQueryService.findMatchingWarning(recipientId, categoryId))
                .thenReturn(Optional.of(new PreferenceWarningResult(categoryId, "패션")));

        GiftPreflightResponse response = giftService.preflight(senderId, request);

        assertThat(response.preferenceWarning())
                .isEqualTo(new GiftPreflightResponse.PreferenceWarning(categoryId, "패션"));
    }

    @Test
    @DisplayName("자기 자신에게 선물하면 INVALID_REQUEST가 발생하고 조회하지 않는다")
    void preflight_throwsInvalidRequest_whenSendingToSelf() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 1L, 1);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.INVALID_REQUEST);

        verifyNoInteractions(userQueryService, friendQueryService, productQueryService, preferenceQueryService);
    }

    @Test
    @DisplayName("활성 수신자가 없으면 RECIPIENT_NOT_FOUND가 발생한다")
    void preflight_throwsRecipientNotFound_whenRecipientIsNotActive() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        when(userQueryService.findActiveUser(2L)).thenReturn(Optional.empty());

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.RECIPIENT_NOT_FOUND);

        verifyNoInteractions(friendQueryService, productQueryService, preferenceQueryService);
    }

    @Test
    @DisplayName("수신자가 친구가 아니면 RECIPIENT_NOT_FRIEND가 발생한다")
    void preflight_throwsRecipientNotFriend_whenRelationshipDoesNotExist() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        when(userQueryService.findActiveUser(2L))
                .thenReturn(Optional.of(new ActiveUserSummary(2L, "수신자")));
        when(friendQueryService.areFriends(1L, 2L)).thenReturn(false);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.RECIPIENT_NOT_FRIEND);

        verifyNoInteractions(productQueryService, preferenceQueryService);
    }

    @Test
    @DisplayName("상품이 없으면 PRODUCT_NOT_FOUND가 발생한다")
    void preflight_throwsProductNotFound_whenProductDoesNotExist() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(3L)).thenReturn(Optional.empty());

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("상품 재고가 요청 수량보다 적으면 INSUFFICIENT_STOCK이 발생한다")
    void preflight_throwsInsufficientStock_whenQuantityExceedsStock() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 3);
        Product product = mock(Product.class);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(3L)).thenReturn(Optional.of(product));
        when(product.getQuantity()).thenReturn(2);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.INSUFFICIENT_STOCK);

        verifyNoInteractions(preferenceQueryService);
    }

    @Test
    @DisplayName("멱등 키에 해당하는 기존 선물 이력이 없으면 빈 결과를 반환한다")
    void findExistingGift_returnsEmpty_whenGiftDoesNotExist() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty());

        Optional<GiftHistory> result = giftService.findExistingGift(senderId, idempotencyKey, "a".repeat(64));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("멱등 키와 fingerprint가 같으면 기존 선물 이력을 반환한다")
    void findExistingGift_returnsGift_whenFingerprintMatches() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftHistory giftHistory = mock(GiftHistory.class);
        when(giftHistory.getRequestFingerprint()).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.of(giftHistory));

        Optional<GiftHistory> result = giftService.findExistingGift(senderId, idempotencyKey, fingerprint);

        assertThat(result).containsSame(giftHistory);
    }

    @Test
    @DisplayName("같은 멱등 키를 다른 요청에 사용하면 IDEMPOTENCY_KEY_CONFLICT가 발생한다")
    void findExistingGift_throwsConflict_whenFingerprintDiffers() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        GiftHistory giftHistory = mock(GiftHistory.class);
        when(giftHistory.getRequestFingerprint()).thenReturn("a".repeat(64));
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.of(giftHistory));

        assertThatThrownBy(() -> giftService.findExistingGift(senderId, idempotencyKey, "b".repeat(64)))
                .isInstanceOfSatisfying(GiftException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    }

    @Test
    @DisplayName("기존 멱등 이력이 있으면 재생성 없이 그 결과를 반환한다")
    void createGift_returnsExistingGift_withoutCallingCommandService_whenIdempotentResultAlreadyExists() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));
        GiftHistory existing = mock(GiftHistory.class);
        when(existing.getRequestFingerprint()).thenReturn(fingerprint);

        when(fingerprintGenerator.generate(request)).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.of(existing));

        GiftHistory result = giftService.createGift(senderId, idempotencyKey, request);

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(giftCommandService);
    }

    @Test
    @DisplayName("기존 멱등 이력이 없으면 GiftCommandService로 신규 생성을 위임한다")
    void createGift_delegatesToCommandService_whenNoExistingResult() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));
        GiftHistory created = mock(GiftHistory.class);

        when(fingerprintGenerator.generate(request)).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(giftCommandService.createNewGift(senderId, idempotencyKey, fingerprint, request))
                .thenReturn(created);

        GiftHistory result = giftService.createGift(senderId, idempotencyKey, request);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("같은 멱등 키의 다른 요청이면 신규 생성을 시도하지 않고 IDEMPOTENCY_KEY_CONFLICT를 전파한다")
    void createGift_propagatesConflict_withoutCallingCommandService_whenExistingFingerprintDiffers() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));
        GiftHistory existing = mock(GiftHistory.class);
        when(existing.getRequestFingerprint()).thenReturn("a".repeat(64));

        when(fingerprintGenerator.generate(request)).thenReturn("b".repeat(64));
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.of(existing));

        assertGiftError(
                () -> giftService.createGift(senderId, idempotencyKey, request),
                ErrorCode.IDEMPOTENCY_KEY_CONFLICT
        );

        verifyNoInteractions(giftCommandService);
    }

    @Test
    @DisplayName("멱등 키 UNIQUE 위반이면 트랜잭션 밖에서 재조회한 기존 이력을 반환한다")
    void createGift_recoversExistingGift_whenCommandServiceThrowsIdempotencyUniqueViolation() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));
        GiftHistory existing = mock(GiftHistory.class);
        when(existing.getRequestFingerprint()).thenReturn(fingerprint);

        when(fingerprintGenerator.generate(request)).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(giftCommandService.createNewGift(senderId, idempotencyKey, fingerprint, request))
                .thenThrow(idempotencyKeyUniqueViolation());

        GiftHistory result = giftService.createGift(senderId, idempotencyKey, request);

        assertThat(result).isSameAs(existing);
    }

    @Test
    @DisplayName("멱등 키 UNIQUE 위반 후 재조회해도 이력이 없으면 INTERNAL_SERVER_ERROR가 발생한다")
    void createGift_throwsInternalServerError_whenRecoveryFindsNoExistingGift() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));

        when(fingerprintGenerator.generate(request)).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(giftCommandService.createNewGift(senderId, idempotencyKey, fingerprint, request))
                .thenThrow(idempotencyKeyUniqueViolation());

        assertGiftError(
                () -> giftService.createGift(senderId, idempotencyKey, request),
                ErrorCode.INTERNAL_SERVER_ERROR
        );
    }

    @Test
    @DisplayName("멱등 키 제약이 아닌 다른 무결성 위반은 재조회하지 않고 그대로 전파한다")
    void createGift_propagatesOtherConstraintViolation_withoutRecovering() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));
        DataIntegrityViolationException otherViolation = otherConstraintViolation();

        when(fingerprintGenerator.generate(request)).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(giftCommandService.createNewGift(senderId, idempotencyKey, fingerprint, request))
                .thenThrow(otherViolation);

        assertThatThrownBy(() -> giftService.createGift(senderId, idempotencyKey, request))
                .isSameAs(otherViolation);

        verify(giftHistoryRepository, times(1)).findBySender_IdAndIdempotencyKey(senderId, idempotencyKey);
    }

    @Test
    @DisplayName("재고 부족처럼 무결성 위반이 아닌 예외는 재조회 없이 그대로 전파한다")
    void createGift_propagatesNonIntegrityException_withoutRecovering() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(3L, 2L, 1, BigDecimal.valueOf(10_000));

        when(fingerprintGenerator.generate(request)).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(giftCommandService.createNewGift(senderId, idempotencyKey, fingerprint, request))
                .thenThrow(new GiftException(ErrorCode.INSUFFICIENT_STOCK));

        assertGiftError(
                () -> giftService.createGift(senderId, idempotencyKey, request),
                ErrorCode.INSUFFICIENT_STOCK
        );

        verify(giftHistoryRepository, times(1)).findBySender_IdAndIdempotencyKey(senderId, idempotencyKey);
    }

    private DataIntegrityViolationException idempotencyKeyUniqueViolation() {
        SQLException sqlException = new SQLException(
                "Duplicate entry",
                "23000",
                1062
        );

        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "제약 위반",
                sqlException,
                "uk_gift_histories_sender_idempotency"
        );

        return new DataIntegrityViolationException("멱등 키 충돌", constraintViolation);
    }

    private DataIntegrityViolationException otherConstraintViolation() {
        SQLException sqlException = new SQLException(
                "Check constraint violated",
                "23000",
                4025
        );

        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "다른 제약 위반",
                sqlException,
                "chk_gift_histories_quantity_positive"
        );

        return new DataIntegrityViolationException("다른 제약 위반", constraintViolation);
    }

    private void stubValidRecipientAndFriend() {
        when(userQueryService.findActiveUser(2L))
                .thenReturn(Optional.of(new ActiveUserSummary(2L, "수신자")));
        when(friendQueryService.areFriends(1L, 2L)).thenReturn(true);
    }

    private void assertGiftError(Runnable action, ErrorCode expectedErrorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(GiftException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode));
    }
}
