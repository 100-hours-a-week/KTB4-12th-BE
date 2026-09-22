package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.gift.repository.GiftCountRow;
import com.gift.gift.domain.gift.service.GiftQueryService;
import com.gift.gift.domain.user.dto.request.UpdateUserProfileRequest;
import com.gift.gift.domain.user.dto.response.UpdateUserProfileResponse;
import com.gift.gift.domain.user.dto.response.UserProfileResponse;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private static final Long USER_ID = 1L;
    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    private UserRepository userRepository;
    private GiftQueryService giftQueryService;
    private UserProfileService userProfileService;
    private ObjectMapper objectMapper;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        giftQueryService = mock(GiftQueryService.class);
        objectMapper = new ObjectMapper();

        Clock clock = Clock.fixed(
                Instant.parse("2026-09-21T03:00:00Z"),
                SEOUL
        );

        userProfileService = new UserProfileService(
                userRepository,
                giftQueryService,
                clock
        );

        user = new User(
                "user@example.com",
                "$2a$12$" + "a".repeat(53),
                "홍길동",
                LocalDate.of(1998, 3, 15)
        );

        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(
                user,
                "updatedAt",
                LocalDateTime.of(2026, 9, 21, 12, 0)
        );
    }

    @Test
    @DisplayName("회원정보와 올해 선물 건수 및 집계 기간을 반환한다")
    void getMyProfile_returnsUserAndYearlyGiftSummary() {
        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                USER_ID,
                UserStatus.ACTIVE
        )).thenReturn(Optional.of(user));

        when(giftQueryService.getYearlyGiftCount(
                USER_ID,
                LocalDate.of(2026, 9, 21)
        )).thenReturn(new GiftCountRow(12L, 8L));

        UserProfileResponse response =
                userProfileService.getMyProfile(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.name()).isEqualTo("홍길동");
        assertThat(response.email())
                .isEqualTo("user@example.com");
        assertThat(response.birth())
                .isEqualTo("1998-03-15");
        assertThat(response.isBirthdayPublic()).isFalse();
        assertThat(response.giftSummary().sentCount())
                .isEqualTo(12L);
        assertThat(response.giftSummary().receivedCount())
                .isEqualTo(8L);
        assertThat(response.giftSummary().period().from())
                .isEqualTo("2026-01-01T00:00+09:00");
        assertThat(response.giftSummary().period().to())
                .isEqualTo("2026-09-21T23:59:59+09:00");
    }

    @Test
    @DisplayName("생년월일만 전달하면 공개 여부를 유지한다")
    void updateMyProfile_updatesOnlyBirth() throws Exception {
        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                USER_ID,
                UserStatus.ACTIVE
        )).thenReturn(Optional.of(user));

        UpdateUserProfileRequest request = request("""
                {
                  "birth": "2000-01-02"
                }
                """);

        UpdateUserProfileResponse response =
                userProfileService.updateMyProfile(
                        USER_ID,
                        request
                );

        assertThat(user.getBirth())
                .isEqualTo("2000-01-02");
        assertThat(user.isBirthdayPublic()).isFalse();
        assertThat(response.birth())
                .isEqualTo("2000-01-02");
        assertThat(response.isBirthdayPublic()).isFalse();

        verify(userRepository).flush();
    }

    @Test
    @DisplayName("생일 공개 여부만 전달하면 생년월일을 유지한다")
    void updateMyProfile_updatesOnlyBirthdayPublic()
            throws Exception {
        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                USER_ID,
                UserStatus.ACTIVE
        )).thenReturn(Optional.of(user));

        UpdateUserProfileRequest request = request("""
                {
                  "isBirthdayPublic": true
                }
                """);

        UpdateUserProfileResponse response =
                userProfileService.updateMyProfile(
                        USER_ID,
                        request
                );

        assertThat(user.getBirth())
                .isEqualTo("1998-03-15");
        assertThat(user.isBirthdayPublic()).isTrue();
        assertThat(response.isBirthdayPublic()).isTrue();

        verify(userRepository).flush();
    }

    @Test
    @DisplayName("활성 회원이 없으면 USER_NOT_FOUND가 발생한다")
    void getMyProfile_throwsUserNotFound() {
        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                USER_ID,
                UserStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> userProfileService.getMyProfile(USER_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND)
        );
    }

    private UpdateUserProfileRequest request(String json)
            throws Exception {
        return objectMapper.readValue(
                json,
                UpdateUserProfileRequest.class
        );
    }
}
