package com.gift.gift.domain.user.controller;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.dto.request.UserSearchRequest;
import com.gift.gift.domain.user.dto.response.UserSearchResponse;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.response.UserSuccessCode;
import com.gift.gift.domain.user.service.UserSearchService;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserSearchController {

    private static final Set<String> ALLOWED_QUERY_PARAMETERS =
            Set.of("email");

    private final UserSearchService userSearchService;

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<UserSearchResponse>>
    search(
            @Valid @ModelAttribute UserSearchRequest request,
            BindingResult bindingResult,
            HttpServletRequest servletRequest
    ) {
        if (bindingResult.hasErrors()
                || hasInvalidQueryParameters(servletRequest)) {
            throw new UserException(
                    UserErrorCode.INVALID_USER_SEARCH_REQUEST
            );
        }

        UserSearchResponse response =
                userSearchService.search(request.email());

        UserSuccessCode successCode = response.user() == null
                ? UserSuccessCode.USER_SEARCH_RESULT_NOT_FOUND
                : UserSuccessCode.USER_SEARCH_RESULT_FOUND;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }

    private boolean hasInvalidQueryParameters(
            HttpServletRequest request
    ) {
        Map<String, String[]> parameters =
                request.getParameterMap();

        if (!parameters.keySet().equals(
                ALLOWED_QUERY_PARAMETERS
        )) {
            return true;
        }

        String[] emails = parameters.get("email");

        return emails == null || emails.length != 1;
    }
}
