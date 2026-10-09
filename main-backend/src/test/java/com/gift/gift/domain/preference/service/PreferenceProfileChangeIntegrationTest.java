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

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
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

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        profileRepository.deleteAll();
        dislikeRepository.deleteAll();
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
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "테스트",
                LocalDate.of(1990, 1, 1)
        ));
    }

    private Category saveRootCategory(String prefix) {
        return categoryRepository.saveAndFlush(new Category(
                prefix + "-" + UUID.randomUUID(),
                null
        ));
    }
}
