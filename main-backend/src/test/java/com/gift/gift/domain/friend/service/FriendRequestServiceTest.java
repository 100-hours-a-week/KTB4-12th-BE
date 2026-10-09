package com.gift.gift.domain.friend.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.friend.dto.request.FriendRequestCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.entity.FriendRequestStatus;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.repository.FriendRequestRepository;
import com.gift.gift.domain.friend.repository.FriendshipRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FriendRequestServiceTest {
    private FriendRequestCommandService commands;
    private FriendRequestRepository requests;
    private FriendRequestService service;

    @BeforeEach
    void setUp() {
        commands = mock(FriendRequestCommandService.class);
        requests = mock(FriendRequestRepository.class);
        service = new FriendRequestService(commands, requests, mock(OpaqueCursorCodec.class));
    }

    @ParameterizedTest
    @EnumSource(value = FriendRequestStatus.class, names = {"ACCEPTED", "REJECTED", "CANCELED"})
    @DisplayName("종료 요청 재처리는 권한 확인 후 성공하며 관계와 알림을 다시 만들지 않는다")
    void complete_doesNotRepeatSideEffectsOrBypassAuthorization(FriendRequestStatus terminal) {
        FriendshipRepository friendships = mock(FriendshipRepository.class);
        UserRepository users = mock(UserRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        FriendRequestCommandService command = new FriendRequestCommandService(requests, friendships, users, events);
        FriendRequest existing = request(1L, 2L);
        existing.complete(terminal);
        when(requests.findForUpdate(10L)).thenReturn(Optional.of(existing));
        Long actor = terminal == FriendRequestStatus.CANCELED ? 1L : 2L;
        assertThat(invoke(command, actor, terminal).status()).isEqualTo(terminal);
        assertThatThrownBy(() -> invoke(command, actor == 1L ? 2L : 1L, terminal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_REQUEST_FORBIDDEN));
        assertThatThrownBy(() -> invoke(command, 3L, terminal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_REQUEST_NOT_FOUND));
        verifyNoInteractions(friendships, users, events);
    }

    private FriendRequestResponse invoke(
            FriendRequestCommandService command, Long actor, FriendRequestStatus terminal
    ) {
        return switch (terminal) {
            case ACCEPTED -> command.accept(actor, 10L);
            case REJECTED -> command.reject(actor, 10L);
            case CANCELED -> command.cancel(actor, 10L);
            default -> throw new IllegalArgumentException("종료 상태만 사용");
        };
    }

    @Test
    @DisplayName("동일 방향 UNIQUE 충돌은 실패 트랜잭션 종료 후 중복 요청으로 변환한다")
    void create_translatesPendingUniqueAfterRollback() {
        when(commands.create(1L, 2L)).thenThrow(violation("friend_requests.uk_friend_requests_pending_pair", 1062));
        when(requests.findPendingPair(1L, 2L)).thenReturn(Optional.of(request(1L, 2L)));
        assertThatThrownBy(() -> service.create(1L, new FriendRequestCreateRequest(2L)))
                .isInstanceOfSatisfying(FriendException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_ALREADY_REQUESTED));
        InOrder order = inOrder(commands, requests);
        order.verify(commands).create(1L, 2L);
        order.verify(requests).findPendingPair(1L, 2L);
    }

    @Test
    @DisplayName("역방향 UNIQUE 충돌은 역방향 충돌로 변환한다")
    void create_translatesConverseUnique() {
        when(commands.create(1L, 2L)).thenThrow(violation("`uk_friend_requests_pending_pair`", 1062));
        when(requests.findPendingPair(1L, 2L)).thenReturn(Optional.of(request(2L, 1L)));
        assertThatThrownBy(() -> service.create(1L, new FriendRequestCreateRequest(2L)))
                .isInstanceOfSatisfying(FriendException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONVERSE_REQUEST_EXISTS);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"fk_friend_requests_receiver", "other_unique"})
    @DisplayName("다른 DB 제약 위반을 친구 요청 중복으로 잘못 변환하지 않는다")
    void create_doesNotTranslateOtherConstraints(String constraint) {
        when(commands.create(1L, 2L)).thenThrow(violation(constraint, 1062));
        assertThatThrownBy(() -> service.create(1L, new FriendRequestCreateRequest(2L)))
                .isInstanceOfSatisfying(FriendException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
        verifyNoInteractions(requests);
    }

    @Test
    @DisplayName("중복 오류 번호가 아닌 오류는 같은 제약 이름이어도 중복으로 변환하지 않는다")
    void create_checksMysqlErrorNumber() {
        when(commands.create(1L, 2L)).thenThrow(violation("uk_friend_requests_pending_pair", 1452));
        assertThatThrownBy(() -> service.create(1L, new FriendRequestCreateRequest(2L)))
                .isInstanceOfSatisfying(FriendException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    @Test
    @DisplayName("중복 충돌 직후 요청이 종료됐어도 성공이나 500 대신 409를 반환한다")
    void create_returnsConflictWhenPendingAlreadyEnded() {
        when(commands.create(1L, 2L)).thenThrow(violation("uk_friend_requests_pending_pair", 1062));
        when(requests.findPendingPair(1L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L, new FriendRequestCreateRequest(2L)))
                .isInstanceOfSatisfying(FriendException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_ALREADY_REQUESTED));
    }

    private DataIntegrityViolationException violation(String name, int code) {
        return new DataIntegrityViolationException("test", new ConstraintViolationException(
                "test", new SQLException("test", "23000", code), name));
    }

    private FriendRequest request(Long requesterId, Long receiverId) {
        User requester = new User("a@example.com", "hash", "요청자", LocalDate.of(2000, 1, 1));
        User receiver = new User("b@example.com", "hash", "수신자", LocalDate.of(2000, 1, 1));
        ReflectionTestUtils.setField(requester, "id", requesterId);
        ReflectionTestUtils.setField(receiver, "id", receiverId);
        FriendRequest request = new FriendRequest(requester, receiver);
        ReflectionTestUtils.setField(request, "id", 10L);
        return request;
    }
}
