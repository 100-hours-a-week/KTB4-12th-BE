package com.gift.gift.domain.friend.service;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.friend.dto.request.FriendCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendCreateResponse;
import com.gift.gift.domain.friend.repository.FriendQueryRepository;
import com.gift.gift.domain.friend.repository.FriendRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.OpaqueCursorCodec;
import com.gift.gift.domain.friend.query.FriendPageAssembler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FriendCreateServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long FRIEND_USER_ID = 2L;

    private FriendQueryRepository friendQueryRepository;
    private FriendRepository friendRepository;
    private UserRepository userRepository;
    private OpaqueCursorCodec cursorCodec;
    private FriendPageAssembler pageAssembler;
    private FriendService friendService;

    @BeforeEach
    void setUp() {
        friendQueryRepository = mock(FriendQueryRepository.class);
        friendRepository = mock(FriendRepository.class);
        userRepository = mock(UserRepository.class);
        cursorCodec = mock(OpaqueCursorCodec.class);
        pageAssembler = mock(FriendPageAssembler.class);

        friendService = new FriendService(
                friendQueryRepository,
                friendRepository,
                userRepository,
                cursorCodec,
                pageAssembler
        );
    }

    @Test
    @DisplayName("본인은 친구로 추가할 수 없다")
    void createFriend_rejectsSelf() {
        FriendCreateRequest request =
                new FriendCreateRequest(USER_ID);

        assertThatThrownBy(() ->
                friendService.createFriend(USER_ID, request)
        )
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.FRIEND_CANNOT_ADD_SELF);

        verifyNoInteractions(
                userRepository,
                friendRepository
        );
    }

    @Test
    @DisplayName("친구 대상 사용자가 없으면 USER_NOT_FOUND를 반환한다")
    void createFriend_rejectsMissingFriendUser() {
        User user = user(USER_ID, "등록자");

        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                USER_ID,
                com.gift.gift.domain.user.entity.UserStatus.ACTIVE
        )).thenReturn(Optional.of(user));

        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                FRIEND_USER_ID,
                com.gift.gift.domain.user.entity.UserStatus.ACTIVE
        )).thenReturn(Optional.empty());

        FriendCreateRequest request =
                new FriendCreateRequest(FRIEND_USER_ID);

        assertThatThrownBy(() ->
                friendService.createFriend(USER_ID, request)
        )
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verifyNoInteractions(friendRepository);
    }

    @Test
    @DisplayName("같은 방향의 친구 관계가 이미 있으면 중복 오류를 반환한다")
    void createFriend_rejectsDuplicateRelation() {
        User user = user(USER_ID, "등록자");
        User friendUser = user(FRIEND_USER_ID, "친구");

        stubActiveUsers(user, friendUser);

        when(friendRepository
                .existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                        USER_ID,
                        FRIEND_USER_ID
                ))
                .thenReturn(true);

        FriendCreateRequest request =
                new FriendCreateRequest(FRIEND_USER_ID);

        assertThatThrownBy(() ->
                friendService.createFriend(USER_ID, request)
        )
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.FRIEND_ALREADY_EXISTS);

        verify(friendRepository)
                .existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                        USER_ID,
                        FRIEND_USER_ID
                );
    }

    @Test
    @DisplayName("반대 방향 관계가 있어도 새로운 방향으로 친구를 추가할 수 있다")
    void createFriend_allowsOppositeDirectionRelation() {
        User user = user(USER_ID, "등록자");
        User friendUser = user(FRIEND_USER_ID, "친구");

        stubActiveUsers(user, friendUser);

        when(friendRepository
                .existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                        USER_ID,
                        FRIEND_USER_ID
                ))
                .thenReturn(false);

        FriendCreateRequest request =
                new FriendCreateRequest(FRIEND_USER_ID);

        FriendCreateResponse response =
                friendService.createFriend(USER_ID, request);

        assertThat(response.friendUserId())
                .isEqualTo(FRIEND_USER_ID);
        assertThat(response.friendName())
                .isEqualTo("친구");

        verify(friendRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("정상 요청이면 친구 관계를 저장하고 대상 정보를 반환한다")
    void createFriend_savesRelationAndReturnsFriendInfo() {
        User user = user(USER_ID, "등록자");
        User friendUser = user(FRIEND_USER_ID, "김민지");

        stubActiveUsers(user, friendUser);

        when(friendRepository
                .existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                        USER_ID,
                        FRIEND_USER_ID
                ))
                .thenReturn(false);

        FriendCreateResponse response =
                friendService.createFriend(
                        USER_ID,
                        new FriendCreateRequest(FRIEND_USER_ID)
                );

        assertThat(response)
                .isEqualTo(new FriendCreateResponse(
                        FRIEND_USER_ID,
                        "김민지"
                ));

        verify(friendRepository).saveAndFlush(any());
    }

    private void stubActiveUsers(User user, User friendUser) {
        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                USER_ID,
                com.gift.gift.domain.user.entity.UserStatus.ACTIVE
        )).thenReturn(Optional.of(user));

        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                FRIEND_USER_ID,
                com.gift.gift.domain.user.entity.UserStatus.ACTIVE
        )).thenReturn(Optional.of(friendUser));
    }

    private User user(Long id, String name) {
        User user = new User(
                id + "@example.com",
                "$2a$04$abcdefghijklmnopqrstuu",
                name,
                LocalDate.of(2000, 1, 1)
        );

        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }

        return user;
    }
}
