package com.gift.gift.domain.friend.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.dto.request.FriendRequestCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.repository.FriendRequestRepository;
import com.gift.gift.domain.friend.support.FriendRequestCursor;
import com.gift.gift.global.common.PaginationPolicy;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@RequiredArgsConstructor
public class FriendRequestService {

    private final FriendRequestCommandService commandService;
    private final FriendRequestRepository requestRepository;
    private final OpaqueCursorCodec cursorCodec;

    public FriendRequestResponse create(Long userId, FriendRequestCreateRequest request) {
        try {
            return commandService.create(userId, request.receiverId());
        } catch (DataIntegrityViolationException exception) {
            if (!isPendingPairViolation(exception)) {
                throw new FriendException(FriendErrorCode.FRIEND_REQUEST_CREATE_FAILED, exception);
            }
            // 실패한 INSERT 트랜잭션이 종료된 뒤 충돌한 요청을 조회한다.
            Optional<FriendRequest> existing = requestRepository.findPendingPair(userId, request.receiverId());
            if (existing.isPresent()) {
                throw FriendRequestCommandService.pendingConflict(userId, existing.get());
            }
            throw new FriendException(FriendErrorCode.FRIEND_ALREADY_REQUESTED);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FriendException(FriendErrorCode.FRIEND_REQUEST_CREATE_FAILED, exception);
        }
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<FriendRequestResponse> getRequests(Long userId, boolean received, String rawCursor) {
        FriendRequestCursor cursor = rawCursor == null ? null : cursorCodec.decode(rawCursor, FriendRequestCursor.class);
        if (cursor != null && (!userId.equals(cursor.userId()) || received != cursor.received())) {
            throw new InvalidCursorException();
        }
        try {
            List<FriendRequest> rows = requestRepository.findPendingList(userId, received,
                    cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.requestId(),
                    PageRequest.of(0, PaginationPolicy.CURSOR_FETCH_SIZE));
            boolean hasNext = rows.size() == PaginationPolicy.CURSOR_FETCH_SIZE;
            List<FriendRequest> page = rows.subList(0, Math.min(rows.size(), PaginationPolicy.DEFAULT_PAGE_SIZE));
            String nextCursor = hasNext
                    ? cursorCodec.encode(new FriendRequestCursor(
                            page.getLast().getCreatedAt(), page.getLast().getId(), userId, received))
                    : null;
            List<FriendRequestResponse> items = page.stream().map(FriendRequestResponse::from).toList();
            return CursorPageResponse.from(items, nextCursor, hasNext);
        } catch (RuntimeException exception) {
            throw new FriendException(FriendErrorCode.FRIEND_REQUEST_LIST_FAILED, exception);
        }
    }

    private boolean isPendingPairViolation(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name == null || violation.getSQLException().getErrorCode() != 1062) {
                    return false;
                }
                String normalized = name.replace("`", "");
                return normalized.substring(normalized.lastIndexOf('.') + 1)
                        .equalsIgnoreCase("uk_friend_requests_pending_pair");
            }
        }
        return false;
    }
}
