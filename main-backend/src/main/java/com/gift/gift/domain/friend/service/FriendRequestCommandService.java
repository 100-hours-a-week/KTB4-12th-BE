package com.gift.gift.domain.friend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.entity.FriendRequestStatus;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.friend.event.FriendRequestCreatedEvent;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.repository.FriendRequestRepository;
import com.gift.gift.domain.friend.repository.FriendshipRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FriendRequestCommandService {

    private final FriendRequestRepository requestRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public FriendRequestResponse create(Long userId, Long receiverId) {
        if (userId.equals(receiverId)) {
            throw new FriendException(FriendErrorCode.FRIEND_CANNOT_ADD_SELF);
        }
        User requester = activeUser(userId);
        User receiver = activeUser(receiverId);
        Long first = Math.min(userId, receiverId);
        Long second = Math.max(userId, receiverId);
        if (friendshipRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(first, second)) {
            throw new FriendException(FriendErrorCode.FRIEND_ALREADY_EXISTS);
        }
        requestRepository.findPendingPair(first, second).ifPresent(existing -> {
            throw pendingConflict(userId, existing);
        });
        FriendRequest request = requestRepository.saveAndFlush(new FriendRequest(requester, receiver));
        friendshipRepository.findPairForUpdate(first, second).ifPresent(friendship -> {
            if (friendship.getDeletedAt() == null) {
                throw new FriendException(FriendErrorCode.FRIEND_ALREADY_EXISTS);
            }
        });
        eventPublisher.publishEvent(new FriendRequestCreatedEvent(request.getId(), receiverId, requester.getName()));
        return FriendRequestResponse.from(request);
    }

    private User activeUser(Long userId) {
        return userRepository.findByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    public FriendRequestResponse accept(Long userId, Long requestId) {
        return complete(userId, requestId, FriendRequestStatus.ACCEPTED);
    }

    public FriendRequestResponse reject(Long userId, Long requestId) {
        return complete(userId, requestId, FriendRequestStatus.REJECTED);
    }

    public FriendRequestResponse cancel(Long userId, Long requestId) {
        return complete(userId, requestId, FriendRequestStatus.CANCELED);
    }

    private FriendRequestResponse complete(Long userId, Long requestId, FriendRequestStatus next) {
        FriendRequest request = requestRepository.findForUpdate(requestId)
                .filter(existing -> existing.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND));
        Long requesterId = request.getRequester().getId();
        Long receiverId = request.getReceiver().getId();
        if (!userId.equals(requesterId) && !userId.equals(receiverId)) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }
        Long allowedUserId = next == FriendRequestStatus.CANCELED ? requesterId : receiverId;
        if (!userId.equals(allowedUserId)) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_FORBIDDEN);
        }
        if (request.getStatus() == next) {
            return FriendRequestResponse.from(request);
        }
        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_ALREADY_PROCESSED);
        }
        if (next == FriendRequestStatus.ACCEPTED) {
            User requester = activeUser(requesterId);
            User receiver = activeUser(receiverId);
            friendshipRepository.findPairForUpdate(Math.min(requesterId, receiverId), Math.max(requesterId, receiverId))
                    .ifPresentOrElse(friendship -> {
                        if (friendship.getDeletedAt() != null) {
                            friendship.restore();
                        }
                    }, () -> friendshipRepository.saveAndFlush(new Friendship(requester, receiver)));
        }
        request.complete(next);
        return FriendRequestResponse.from(request);
    }

    static BusinessException pendingConflict(Long userId, FriendRequest existing) {
        return existing.getRequester().getId().equals(userId)
                ? new FriendException(FriendErrorCode.FRIEND_ALREADY_REQUESTED)
                : new FriendException(FriendErrorCode.CONVERSE_REQUEST_EXISTS);
    }
}
