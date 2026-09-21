package com.gift.gift.domain.preference.repository;

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
}
