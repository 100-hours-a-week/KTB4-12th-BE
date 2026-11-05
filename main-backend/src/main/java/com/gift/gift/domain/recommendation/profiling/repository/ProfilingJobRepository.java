package com.gift.gift.domain.recommendation.profiling.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

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
}
