package com.gift.gift.domain.recommendation.profiling.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.preference.entity.UserGiftPreference;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.support.AiProfilingProperties;
import com.gift.gift.domain.review.repository.ProfileReviewRow;
import com.gift.gift.domain.review.repository.ReviewRepository;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProfileInputTest {

    @Test
    @DisplayName("최신 열 건 안에서 상품별 첫 리뷰만 유지하고 취향 원문을 포함한다")
    void dispatch_keepsLatestReviewPerProductWithoutRefilling() {
        Clock clock = Clock.systemUTC();
        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User("test@example.com", "hash", "테스트", LocalDate.of(2000, 1, 1));
        ReflectionTestUtils.setField(user, "id", 10L);
        RecipientProfile profile = new RecipientProfile(user);
        ReflectionTestUtils.setField(profile, "id", 1L);
        profile.recordPreferenceChange(now.minusHours(2));

        RecipientProfileRepository profiles = mock(RecipientProfileRepository.class);
        UserDislikeCategoryRepository dislikes = mock(UserDislikeCategoryRepository.class);
        UserGiftPreferenceRepository preferences = mock(UserGiftPreferenceRepository.class);
        ReviewRepository reviews = mock(ReviewRepository.class);
        when(profiles.findByIdForUpdate(1L)).thenReturn(Optional.of(profile));
        when(profiles.findDispatchClaimNow()).thenReturn(now);
        when(preferences.findByUser_IdAndDeletedAtIsNull(10L))
                .thenReturn(Optional.of(new UserGiftPreference(user, "차를 좋아해요.")));
        when(reviews.findLatestProfileReviews(10L, PageRequest.of(0, 10))).thenReturn(List.of(
                new ProfileReviewRow(100L, 5, "최근 리뷰"),
                new ProfileReviewRow(100L, 1, "오래된 리뷰"),
                new ProfileReviewRow(200L, 2, " \t")
        ));
        AiProfilingProperties properties = new AiProfilingProperties(URI.create("http://ai.example.test"), "test-token",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofHours(6),
                100, 50, Duration.ofMinutes(2));
        ProfileDispatchTransactionService service = new ProfileDispatchTransactionService(
                profiles, dislikes, preferences, reviews, properties, clock
        );

        AiProfileRequest request = service.prepareDispatch(1L).orElseThrow().request();

        assertThat(request.giftPreference()).isEqualTo("차를 좋아해요.");
        assertThat(request.reviews()).extracting(review -> review.productId()).containsExactly(100L, 200L);
        assertThat(request.reviews().getFirst().rating()).isEqualTo(5);
        assertThat(request.reviews().getFirst().reviewText()).isEqualTo("최근 리뷰");
        assertThat(request.reviews().getLast().reviewText()).isNull();
        verify(reviews).findLatestProfileReviews(10L, PageRequest.of(0, 10));
        verifyNoMoreInteractions(reviews);
    }
}
