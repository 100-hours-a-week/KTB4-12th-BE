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

import com.gift.gift.domain.recommendation.profiling.entity.ProfilingJob;

public interface ProfilingJobRepository
        extends JpaRepository<ProfilingJob, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job
            from ProfilingJob job
            where job.profile.id = :profileId
              and job.sourceVersion = :sourceVersion
            """)
    Optional<ProfilingJob> findByProfileVersionForUpdate(
            @Param("profileId") Long profileId,
            @Param("sourceVersion") long sourceVersion
    );

    @Query("""
            select job.requestId as requestId,
                   job.profile.id as profileId,
                   job.sourceVersion as sourceVersion,
                   job.profile.sourceVersion as currentSourceVersion,
                   job.profile.analyzedSourceVersion as analyzedSourceVersion,
                   job.attemptCount as attemptCount,
                   job.lastErrorCode as lastErrorCode,
                   job.updatedAt as updatedAt
            from ProfilingJob job
            where job.publishStatus =
                com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus.BLOCKED
              and job.resultStatus =
                com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus.WAITING
            order by job.createdAt asc, job.requestId asc
            """)
    List<BlockedJobView> findBlockedJobs(Pageable pageable);

    interface BlockedJobView {

        String getRequestId();

        Long getProfileId();

        long getSourceVersion();

        long getCurrentSourceVersion();

        long getAnalyzedSourceVersion();

        int getAttemptCount();

        String getLastErrorCode();

        LocalDateTime getUpdatedAt();
    }

    @Query("""
        select job.profile.id as profileId,
               job.profile.recipient.id as recipientId
        from ProfilingJob job
        where job.requestId = :requestId
        """)
    Optional<ResultTargetView> findResultTarget(
            @Param("requestId") String requestId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select job
        from ProfilingJob job
        where job.requestId = :requestId
        """)
    Optional<ProfilingJob> findByRequestIdForUpdate(
            @Param("requestId") String requestId
    );

    interface ResultTargetView {

        Long getProfileId();

        Long getRecipientId();
    }
}
