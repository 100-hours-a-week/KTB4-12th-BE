package com.gift.gift.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndStatusAndDeletedAtIsNull(
            Long id,
            UserStatus status
    );

    Optional<User> findByEmailAndStatusAndDeletedAtIsNull(
            String email,
            UserStatus status
    );
}
