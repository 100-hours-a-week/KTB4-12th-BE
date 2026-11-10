package com.gift.gift.domain.recommendation.profiling.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;

public interface RecipientProfileRepository
        extends JpaRepository<RecipientProfile, Long> {

    @Query("""
            select profile.recipient.id
            from RecipientProfile profile
            where profile.id = :profileId
            """)
    Optional<Long> findRecipientIdByProfileId(
            @Param("profileId") Long profileId
    );

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
            where profile.lastChangedAt is not null
              and profile.windowStartedAt is not null
              and (
                    profile.lastChangedAt <= :quietPeriodCutoff
                    or profile.windowStartedAt <= :maxWindowCutoff
              )
              and not exists (
                    select job.requestId
                    from ProfilingJob job
                    where job.profile.id = profile.id
                      and job.sourceVersion = profile.sourceVersion
                      and job.resultStatus =
                          com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus.WAITING
                      and job.publishStatus in (
                          com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus.READY,
                          com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus.CLAIMED,
                          com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus.RETRY_WAIT
                      )
              )
            order by profile.windowStartedAt asc, profile.id asc
            """)
    List<Long> findJobPreparationCandidateIds(
            @Param("quietPeriodCutoff")
            LocalDateTime quietPeriodCutoff,
            @Param("maxWindowCutoff")
            LocalDateTime maxWindowCutoff,
            Pageable pageable
    );
}
