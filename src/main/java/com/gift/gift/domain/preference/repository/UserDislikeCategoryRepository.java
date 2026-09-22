package com.gift.gift.domain.preference.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.preference.entity.UserDislikeCategory;

public interface UserDislikeCategoryRepository
        extends JpaRepository<UserDislikeCategory, Long> {

    @Query("""
            select dislike
            from UserDislikeCategory dislike
            join fetch dislike.category category
            where dislike.user.id = :userId
              and category.parent is null
              and exists (
                  select productCategory.id
                  from Category productCategory
                  where productCategory.id = :productCategoryId
                    and productCategory.parent = category
                    and productCategory.deletedAt is null
              )
              and dislike.deletedAt is null
              and category.deletedAt is null
            """)
    Optional<UserDislikeCategory> findActiveByUserIdAndProductCategoryId(
            @Param("userId") Long userId,
            @Param("productCategoryId") Long productCategoryId
    );

    @Query("""
        select dislike.category.id
        from UserDislikeCategory dislike
        where dislike.user.id = :userId
          and dislike.deletedAt is null
          and dislike.category.deletedAt is null
          and dislike.category.parent is null
        """)
    List<Long> findAllActiveCategoryIdsByUserId(
            @Param("userId") Long userId
    );

    @Query("""
        select dislike
        from UserDislikeCategory dislike
        join fetch dislike.category
        where dislike.user.id = :userId
        """)
    List<UserDislikeCategory> findAllByUserIdIncludingDeleted(
            @Param("userId") Long userId
    );
}
