package com.gift.gift.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.dto.response.UserSearchResponse;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSearchService {

    private final UserRepository userRepository;

    public UserSearchResponse search(String email) {
        return userRepository
                .findByEmailAndStatusAndDeletedAtIsNull(
                        email,
                        UserStatus.ACTIVE
                )
                .map(UserSearchResponse::from)
                .orElseGet(UserSearchResponse::notFound);
    }
}
