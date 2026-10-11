package com.gift.gift.domain.recommendation.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;

public interface RecipientProfileRepository
        extends JpaRepository<RecipientProfile, Long> {

    Optional<RecipientProfile> findByRecipient_Id(Long recipientId);

    boolean existsByRecipient_Id(Long recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile
            from RecipientProfile profile
            where profile.id = :profileId
            """)
    Optional<RecipientProfile> findByIdForUpdate(
            @Param("profileId") Long profileId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile
            from RecipientProfile profile
            where profile.recipient.id = :recipientId
            """)
    Optional<RecipientProfile> findByRecipientIdForUpdate(
            @Param("recipientId") Long recipientId
    );

    @Query("""
        select profile.id
        from RecipientProfile profile
        where (
            profile.dispatchClaimToken is null
            and profile.lastChangedAt is not null
            and profile.windowStartedAt is not null
            and (
                profile.lastChangedAt <= :quietPeriodCutoff
                or profile.windowStartedAt <= :maxWindowCutoff
            )
        )
        or (
            profile.dispatchClaimToken is not null
            and profile.dispatchClaimedAt <= :claimExpiredCutoff
        )
        order by
            case
                when profile.dispatchClaimToken is not null then 0
                else 1
            end asc,
            profile.dispatchClaimedAt asc,
            profile.windowStartedAt asc,
            profile.id asc
        """)
    List<Long> findDispatchCandidateIds(
            @Param("quietPeriodCutoff")
            LocalDateTime quietPeriodCutoff,
            @Param("maxWindowCutoff")
            LocalDateTime maxWindowCutoff,
            @Param("claimExpiredCutoff")
            LocalDateTime claimExpiredCutoff,
            Pageable pageable
    );

    @Query("""
            select profile
            from RecipientProfile profile
            where profile.lastChangedAt is not null
              and (
                    profile.lastChangedAt <= :quietPeriodCutoff
                    or profile.windowStartedAt <= :maxWindowCutoff
              )
            order by profile.windowStartedAt asc, profile.id asc
            """)
    List<RecipientProfile> findDispatchCandidates(
            @Param("quietPeriodCutoff")
            LocalDateTime quietPeriodCutoff,
            @Param("maxWindowCutoff")
            LocalDateTime maxWindowCutoff,
            Pageable pageable
    );

    @Query("""
        select profile.id
        from RecipientProfile profile
        where profile.profileStatus = :pendingStatus
          and profile.pendingSince <= :pendingCutoff
          and profile.lastChangedAt is null
          and profile.sourceVersion > profile.analyzedSourceVersion
          and profile.dispatchClaimLastChangedAt is null
          and (
              profile.dispatchClaimToken is null
              or profile.dispatchClaimedAt <= :claimExpiredCutoff
          )
        order by profile.pendingSince asc, profile.id asc
        """)
    List<Long> findRecoveryCandidateIds(
            @Param("pendingStatus")
            RecipientProfileStatus pendingStatus,
            @Param("pendingCutoff")
            LocalDateTime pendingCutoff,
            @Param("claimExpiredCutoff")
            LocalDateTime claimExpiredCutoff,
            Pageable pageable
    );

    @Query("""
            select profile
            from RecipientProfile profile
            where profile.profileStatus = :pendingStatus
              and profile.pendingSince <= :pendingCutoff
            order by profile.pendingSince asc, profile.id asc
            """)
    List<RecipientProfile> findPendingTimeoutCandidates(
            @Param("pendingStatus")
            RecipientProfileStatus pendingStatus,
            @Param("pendingCutoff")
            LocalDateTime pendingCutoff,
            Pageable pageable
    );

    @Query(value = "SELECT UTC_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime findDispatchClaimNow();
}
