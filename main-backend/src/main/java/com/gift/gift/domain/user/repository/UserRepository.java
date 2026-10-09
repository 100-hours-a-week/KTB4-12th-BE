package com.gift.gift.domain.user.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u
        from User u
        where u.id = :userId
          and u.status = :status
          and u.deletedAt is null
        """)
    Optional<User> findActiveByIdForUpdate(
            @Param("userId") Long userId,
            @Param("status") UserStatus status
    );
}
