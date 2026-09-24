package com.gift.gift.domain.friend.controller;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.friend.dto.request.FriendCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendCreateResponse;
import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.dto.response.FriendListResponse;
import com.gift.gift.domain.friend.dto.response.FriendSearchResponse;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.response.FriendSuccessCode;
import com.gift.gift.domain.friend.service.FriendService;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
public class FriendController {

    private static final Set<String>
            FRIEND_SEARCH_QUERY_PARAMETERS =
            Set.of(
                    "query",
                    "cursor"
            );

    private final FriendService friendService;

    @GetMapping
    public ResponseEntity<ApiResponse<FriendListResponse>>
    getFriends(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String cursor
    ) {
        CursorPageResponse<FriendListItem> response =
                friendService.getFriends(
                        userId,
                        cursor
                );

        FriendSuccessCode successCode =
                FriendSuccessCode.FRIEND_LIST_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        FriendListResponse.from(response)
                ));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<FriendSearchResponse>>
    searchFriends(
            @CurrentUserId Long userId,
            @RequestParam
            MultiValueMap<String, String> queryParameters
    ) {
        validateSearchParameters(queryParameters);

        String normalizedQuery = normalizeQuery(
                queryParameters.getFirst("query")
        );

        String cursor = queryParameters.getFirst("cursor");

        CursorPageResponse<FriendListItem> page =
                friendService.searchFriends(
                        userId,
                        normalizedQuery,
                        cursor
                );

        FriendSuccessCode successCode =
                FriendSuccessCode
                        .FRIEND_SEARCH_RESULT_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        FriendSearchResponse.from(page)
                ));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FriendCreateResponse>>
    createFriend(
            @CurrentUserId Long userId,
            @Valid @RequestBody FriendCreateRequest request
    ) {
        FriendCreateResponse response =
                friendService.createFriend(
                        userId,
                        request
                );

        FriendSuccessCode successCode =
                FriendSuccessCode.FRIEND_ADDED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.formatMessage(
                                response.friendName()
                        ),
                        response
                ));
    }

    private void validateSearchParameters(
            MultiValueMap<String, String> queryParameters
    ) {
        validateAllowedParameterNames(queryParameters);
        validateQueryMultiplicity(queryParameters);
        validateCursorMultiplicity(queryParameters);
        validateRequiredQuery(queryParameters);
    }

    private void validateAllowedParameterNames(
            MultiValueMap<String, String> queryParameters
    ) {
        if (!FRIEND_SEARCH_QUERY_PARAMETERS.containsAll(
                queryParameters.keySet()
        )) {
            throw new FriendException(
                    FriendErrorCode
                            .FRIEND_SEARCH_QUERY_REQUIRED
            );
        }
    }

    private void validateQueryMultiplicity(
            MultiValueMap<String, String> queryParameters
    ) {
        List<String> queries =
                queryParameters.get("query");

        if (queries != null && queries.size() != 1) {
            throw new FriendException(
                    FriendErrorCode
                            .FRIEND_SEARCH_QUERY_REQUIRED
            );
        }
    }

    private void validateCursorMultiplicity(
            MultiValueMap<String, String> queryParameters
    ) {
        List<String> cursors =
                queryParameters.get("cursor");

        if (cursors != null && cursors.size() != 1) {
            throw new InvalidCursorException();
        }
    }

    private void validateRequiredQuery(
            MultiValueMap<String, String> queryParameters
    ) {
        String query =
                queryParameters.getFirst("query");

        if (query == null || query.isBlank()) {
            throw new FriendException(
                    FriendErrorCode
                            .FRIEND_SEARCH_QUERY_REQUIRED
            );
        }
    }

    private String normalizeQuery(String query) {
        return query.strip()
                .toLowerCase(Locale.ROOT);
    }
}
