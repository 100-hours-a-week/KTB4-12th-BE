package com.gift.gift.domain.preference.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest
class PreferenceProfileChangeIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant FIRST_INSTANT =
            Instant.parse("2026-09-27T01:00:00Z");
    private static final Instant SECOND_INSTANT =
            Instant.parse("2026-09-27T02:00:00Z");

    @Autowired
    private PreferenceSaveService preferenceSaveService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private UserDislikeCategoryRepository dislikeRepository;

    @Autowired
    private UserGiftPreferenceRepository giftPreferenceRepository;

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        creditTransactionRepository.deleteAll();
        creditAccountRepository.deleteAll();
        profileRepository.deleteAll();
        dislikeRepository.deleteAll();
        giftPreferenceRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
        setCurrentTime(FIRST_INSTANT);
    }

    @Test
    @DisplayName("실제 비선호 선택 변경 시 프로파일 변경 시각을 기록한다")
    void save_recordsProfileChangeWhenSelectionActuallyChanges() {
        User user = saveUser();
        Category category = saveRootCategory("뷰티");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(category.getId())
        );

        RecipientProfile profile = findProfile(user.getId());
        assertThat(profile.getLastChangedAt()).isEqualTo(firstTime());
        assertThat(profile.getWindowStartedAt()).isEqualTo(firstTime());
    }

    @Test
    @DisplayName("동일한 비선호 집합을 다시 저장하면 변경 시각을 갱신하지 않는다")
    void save_doesNotUpdateTimestampForSameSelectionSet() {
        User user = saveUser();
        Category beauty = saveRootCategory("뷰티");
        Category food = saveRootCategory("식품");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(beauty.getId(), food.getId())
        );
        setCurrentTime(SECOND_INSTANT);

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(food.getId(), beauty.getId())
        );

        RecipientProfile profile = findProfile(user.getId());
        assertThat(profile.getLastChangedAt()).isEqualTo(firstTime());
        assertThat(profile.getWindowStartedAt()).isEqualTo(firstTime());
    }

    @Test
    @DisplayName("최초 미반영 변경 시 Window 시작 시각을 함께 기록한다")
    void save_recordsWindowStartAtFirstUnreflectedChange() {
        User user = saveUser();
        Category category = saveRootCategory("리빙");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(category.getId())
        );

        RecipientProfile profile = findProfile(user.getId());
        assertThat(profile.getWindowStartedAt())
                .isEqualTo(profile.getLastChangedAt())
                .isEqualTo(firstTime());
    }

    @Test
    @DisplayName("같은 대기 구간의 연속 변경은 최초 Window 시작 시각을 유지한다")
    void save_keepsOriginalWindowStartForSubsequentChange() {
        User user = saveUser();
        Category beauty = saveRootCategory("뷰티");
        Category food = saveRootCategory("식품");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(beauty.getId())
        );
        setCurrentTime(SECOND_INSTANT);

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(food.getId())
        );

        RecipientProfile profile = findProfile(user.getId());
        assertThat(profile.getLastChangedAt()).isEqualTo(secondTime());
        assertThat(profile.getWindowStartedAt()).isEqualTo(firstTime());
    }

    @Test
    @DisplayName("비선호 전체 해제도 실제 변경으로 기록한다")
    void save_recordsChangeWhenAllSelectionsAreCleared() {
        User user = saveUser();
        Category category = saveRootCategory("디지털");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(category.getId())
        );
        setCurrentTime(SECOND_INSTANT);

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of()
        );

        RecipientProfile profile = findProfile(user.getId());
        assertThat(profile.getLastChangedAt()).isEqualTo(secondTime());
        assertThat(profile.getWindowStartedAt()).isEqualTo(firstTime());
    }

    @Test
    @DisplayName("취향 최초 저장과 실제 변경·비우기를 프로파일 변경으로 기록한다")
    void saveGiftPreference_recordsOnlyActualChanges() {
        User user = saveUser();

        preferenceSaveService.saveGiftPreference(user.getId(), "차를 좋아해요.");
        RecipientProfile profile = findProfile(user.getId());
        assertThat(profile.getLastChangedAt()).isEqualTo(firstTime());

        preferenceSaveService.saveGiftPreference(user.getId(), " 차를 좋아해요. ");
        assertThat(profile.getLastChangedAt()).isEqualTo(firstTime());

        setCurrentTime(SECOND_INSTANT);
        preferenceSaveService.saveGiftPreference(user.getId(), null);
        RecipientProfile updatedProfile = findProfile(user.getId());
        assertThat(updatedProfile.getLastChangedAt()).isEqualTo(secondTime());
        assertThat(updatedProfile.getWindowStartedAt()).isEqualTo(firstTime());
        assertThat(creditAccountRepository.findByUserId(user.getId())
                .orElseThrow().getBalance()).isEqualTo(97L);
        assertThat(creditTransactionRepository.findAll())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.getType()).isEqualTo(
                            CreditTransactionType.GIFT_PREFERENCE_UPDATE_USE
                    );
                    assertThat(transaction.getAmount()).isEqualTo(3L);
                });
    }

    @Test
    @DisplayName("온보딩 후 비선호 카테고리를 실제 변경하면 3크레딧을 차감한다")
    void saveDislikeCategories_chargesAfterOnboarding() {
        User user = saveUser();
        Category beauty = saveRootCategory("뷰티");
        Category food = saveRootCategory("식품");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(beauty.getId())
        );
        user.completeOnboarding();
        userRepository.saveAndFlush(user);

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(food.getId())
        );
        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(food.getId())
        );

        assertThat(creditAccountRepository.findByUserId(user.getId())
                .orElseThrow().getBalance()).isEqualTo(97L);
        assertThat(creditTransactionRepository.findAll())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.getType()).isEqualTo(
                            CreditTransactionType.DISLIKE_CATEGORY_UPDATE_USE
                    );
                    assertThat(transaction.getAmount()).isEqualTo(3L);
                });
    }

    @Test
    @DisplayName("크레딧이 부족하면 자연어 취향 수정과 원장 저장을 모두 거부한다")
    void saveGiftPreference_rejectsUpdateWhenCreditIsInsufficient() {
        User user = saveUser(2L);
        preferenceSaveService.saveGiftPreference(user.getId(), "향수를 좋아해요.");

        assertThatThrownBy(() -> preferenceSaveService.saveGiftPreference(
                user.getId(),
                "차를 좋아해요."
        )).isInstanceOfSatisfying(
                PreferenceException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_CREDIT)
        );

        assertThat(giftPreferenceRepository
                .findByUser_IdAndDeletedAtIsNull(user.getId())
                .orElseThrow().getPreference())
                .isEqualTo("향수를 좋아해요.");
        assertThat(creditAccountRepository.findByUserId(user.getId())
                .orElseThrow().getBalance()).isEqualTo(2L);
        assertThat(creditTransactionRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("처음부터 비어 있는 취향 저장은 프로파일을 만들지 않는다")
    void saveGiftPreference_doesNotMarkUnchangedEmptyPreference() {
        User user = saveUser();

        preferenceSaveService.saveGiftPreference(user.getId(), " \n ");

        assertThat(profileRepository.findByRecipient_Id(user.getId())).isEmpty();
    }

    private void setCurrentTime(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(ZONE);
    }

    private LocalDateTime firstTime() {
        return LocalDateTime.ofInstant(FIRST_INSTANT, ZONE);
    }

    private LocalDateTime secondTime() {
        return LocalDateTime.ofInstant(SECOND_INSTANT, ZONE);
    }

    private RecipientProfile findProfile(Long userId) {
        return profileRepository.findByRecipient_Id(userId).orElseThrow();
    }

    private User saveUser() {
        return saveUser(100L);
    }

    private User saveUser(long balance) {
        User user = userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "테스트",
                LocalDate.of(1990, 1, 1)
        ));
        creditAccountRepository.saveAndFlush(new CreditAccount(
                user.getId(),
                balance
        ));
        return user;
    }

    private Category saveRootCategory(String prefix) {
        return categoryRepository.saveAndFlush(new Category(
                prefix + "-" + UUID.randomUUID(),
                null
        ));
    }
}
