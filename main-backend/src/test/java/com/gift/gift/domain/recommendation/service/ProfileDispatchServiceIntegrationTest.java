package com.gift.gift.domain.recommendation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.preference.service.PreferenceSaveService;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.infrastructure.ai.AiProfilingClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest
class ProfileDispatchServiceIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW =
            Instant.parse("2026-09-27T03:00:00Z");

    @Autowired
    private ProfileDispatchService profileDispatchService;

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
    private AiProfilingClient aiProfilingClient;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        profileRepository.deleteAll();
        dislikeRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        reset(aiProfilingClient);
        when(aiProfilingClient.isHealthy()).thenReturn(true);

        setCurrentTime(NOW);
    }

    @Test
    @DisplayName("마지막 변경 후 1시간이 지난 프로파일을 요청한다")
    void dispatchDueProfiles_requestsAfterQuietPeriod() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3_601));
        stubAcceptedResponse();

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        verify(aiProfilingClient).requestProfiling(any());
        assertThat(findProfile(user).getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
    }

    @Test
    @DisplayName("최초 변경 후 6시간이 지난 프로파일은 최근 변경이 있어도 요청한다")
    void dispatchDueProfiles_requestsAfterMaximumWindow() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(6 * 3_600));
        Category second = saveRootCategory("식품");
        setCurrentTime(NOW.minusSeconds(30 * 60));
        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(second.getId())
        );
        stubAcceptedResponse();

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        verify(aiProfilingClient).requestProfiling(any());
        assertThat(findProfile(user).getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
    }

    @Test
    @DisplayName("디바운스와 최대 대기 조건을 모두 충족하지 않으면 요청하지 않는다")
    void dispatchDueProfiles_skipsProfileBeforeThresholds() {
        saveChangedPreferenceAt(NOW.minusSeconds(30 * 60));
        setCurrentTime(NOW);

        profileDispatchService.dispatchDueProfiles();

        verify(aiProfilingClient, never()).requestProfiling(any());
    }

    @Test
    @DisplayName("AI 호출 전에 증가한 Source Version이 커밋되고 HTTP 호출에는 DB 트랜잭션이 없다")
    void dispatchDueProfiles_commitsVersionBeforeCallingAiOutsideTransaction() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3_601));
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicBoolean versionCommitted = new AtomicBoolean(false);

        when(aiProfilingClient.requestProfiling(any()))
                .thenAnswer(invocation -> {
                    AiProfileRequest request = invocation.getArgument(0);
                    transactionActive.set(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive()
                    );
                    versionCommitted.set(
                            findProfile(user).getSourceVersion()
                                    == request.sourceVersion()
                    );
                    return accepted(request);
                });

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        assertThat(transactionActive.get()).isFalse();
        assertThat(versionCommitted.get()).isTrue();
        assertThat(findProfile(user).getSourceVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("202 응답 후 PENDING 상태와 접수 시각을 별도 트랜잭션에 저장한다")
    void dispatchDueProfiles_savesAcceptedState() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3_601));
        stubAcceptedResponse();

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        RecipientProfile profile = findProfile(user);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince())
                .isEqualTo(java.time.LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
    }

    private void stubAcceptedResponse() {
        when(aiProfilingClient.requestProfiling(any()))
                .thenAnswer(invocation -> accepted(invocation.getArgument(0)));
    }

    private AiProfileAcceptedResponse accepted(AiProfileRequest request) {
        return new AiProfileAcceptedResponse(
                request.recipientUserId(),
                request.sourceVersion(),
                RecipientProfileStatus.PENDING
        );
    }

    private User saveChangedPreferenceAt(Instant changedAt) {
        User user = saveUser();
        Category category = saveRootCategory("뷰티");
        setCurrentTime(changedAt);
        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(category.getId())
        );
        return user;
    }

    private void setCurrentTime(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(ZONE);
    }

    private RecipientProfile findProfile(User user) {
        return profileRepository.findByRecipient_Id(user.getId())
                .orElseThrow();
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
