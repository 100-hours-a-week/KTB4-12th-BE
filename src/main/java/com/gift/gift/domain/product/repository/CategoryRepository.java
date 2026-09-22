package com.gift.gift.domain.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.gift.gift.domain.product.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    @Query("""
            select c
            from Category c
            left join fetch c.parent
            where c.deletedAt is null
            order by c.id asc
            """)
    List<Category> findAllActiveWithParent();

    @Query("""
        select category
        from Category category
        where category.parent is null
          and category.deletedAt is null
        order by category.id asc
        """)
    List<Category> findAllActiveRootsOrderById();
}
