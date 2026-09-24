package com.gift.gift.domain.friend.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.dto.request.FriendCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendCreateResponse;
import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.query.FriendPage;
import com.gift.gift.domain.friend.query.FriendPageAssembler;
import com.gift.gift.domain.friend.repository.FriendQueryRepository;
import com.gift.gift.domain.friend.repository.FriendRepository;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendService {

    private final FriendQueryRepository friendQueryRepository;
    private final FriendRepository friendRepository;
    private final UserRepository userRepository;
    private final OpaqueCursorCodec cursorCodec;
    private final FriendPageAssembler pageAssembler;

    public CursorPageResponse<FriendListItem> getFriends(
            Long userId,
            String rawCursor
    ) {
        FriendCursor cursor = decodeListCursor(rawCursor);

        try {
            FriendPage page = pageAssembler.assemble(
                    friendQueryRepository.findFriends(
                            userId,
                            cursor
                    )
            );

            return toPageResponse(page);
        } catch (RuntimeException exception) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_LIST_RETRIEVAL_FAILED,
                    exception
            );
        }
    }

    public CursorPageResponse<FriendListItem> searchFriends(
            Long userId,
            String query,
            String rawCursor
    ) {
        validateSearchQuery(query);

        FriendCursor cursor = decodeSearchCursor(
                rawCursor,
                query
        );

        try {
            FriendPage page = pageAssembler.assemble(
                    friendQueryRepository.findFriendsByName(
                            userId,
                            query,
                            cursor
                    ),
                    query
            );

            return toPageResponse(page);
        } catch (RuntimeException exception) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_SEARCH_FAILED,
                    exception
            );
        }
    }

    @Transactional
    public FriendCreateResponse createFriend(
            Long userId,
            FriendCreateRequest request
    ) {
        Long friendUserId = request.friendUserId();

        validateNotSelf(
                userId,
                friendUserId
        );

        User user = findActiveUser(userId);
        User friendUser = findActiveUser(friendUserId);

        validateNotAlreadyFriend(
                userId,
                friendUserId
        );

        saveFriend(
                user,
                friendUser
        );

        return FriendCreateResponse.from(friendUser);
    }

    private CursorPageResponse<FriendListItem> toPageResponse(FriendPage page) {

        List<FriendListItem> items = page.items()
                .stream()
                .map(FriendListItem::from)
                .toList();

        return CursorPageResponse.from(
                items,
                page.nextCursor(),
                page.hasNext()
        );
    }

    private void validateSearchQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_SEARCH_QUERY_REQUIRED
            );
        }
    }

    private FriendCursor decodeListCursor(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }

        FriendCursor cursor = cursorCodec.decode(
                rawCursor,
                FriendCursor.class
        );

        if (cursor.query() != null) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

    private FriendCursor decodeSearchCursor(String rawCursor, String query) {

        if (rawCursor == null) {
            return null;
        }

        FriendCursor cursor = cursorCodec.decode(rawCursor, FriendCursor.class);

        if (!query.equals(cursor.query())) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

    private void validateNotSelf(Long userId, Long friendUserId) {
        if (userId.equals(friendUserId)) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_CANNOT_ADD_SELF
            );
        }
    }

    private User findActiveUser(Long userId) {
        return userRepository
                .findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                )
                .orElseThrow(() -> new UserException(
                        UserErrorCode.USER_NOT_FOUND
                ));
    }

    private void validateNotAlreadyFriend(
            Long userId,
            Long friendUserId
    ) {
        boolean alreadyExists =
                friendRepository
                        .existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                                userId,
                                friendUserId
                        );

        if (alreadyExists) {
            throw new FriendException(
                    FriendErrorCode.FRIEND_ALREADY_EXISTS
            );
        }
    }

    private void saveFriend(
            User user,
            User friendUser
    ) {
        try {
            friendRepository.saveAndFlush(
                    new Friend(
                            user,
                            friendUser
                    )
            );
        } catch (DataIntegrityViolationException exception) {
            if (isFriendUniqueConstraintViolation(exception)) {
                throw new FriendException(
                        FriendErrorCode.FRIEND_ALREADY_EXISTS
                );
            }

            throw exception;
        }
    }

    private boolean isFriendUniqueConstraintViolation(
            Throwable exception
    ) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException violation) {

                String constraintName =
                        violation.getConstraintName();

                if (constraintName == null) {
                    return false;
                }

                String normalizedName =
                        constraintName.replace("`", "");

                int separatorIndex =
                        normalizedName.lastIndexOf('.');

                if (separatorIndex >= 0) {
                    normalizedName =
                            normalizedName.substring(
                                    separatorIndex + 1
                            );
                }

                return "uk_friends_user_friend_user"
                        .equalsIgnoreCase(normalizedName)
                        && violation.getSQLException()
                        .getErrorCode() == 1062;
            }

            current = current.getCause();
        }

        return false;
    }
}
