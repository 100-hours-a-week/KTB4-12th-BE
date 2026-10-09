package com.gift.gift.domain.product.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.product.dto.response.CategoryListResponse;
import com.gift.gift.domain.product.dto.response.CategoryResponse;
import com.gift.gift.domain.product.dto.response.CategoryResponse.ChildCategoryResponse;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CategoryQueryServiceTest {

    private CategoryRepository categoryRepository;
    private CategoryQueryService categoryQueryService;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        categoryQueryService = new CategoryQueryService(
                categoryRepository
        );
    }

    @Test
    @DisplayName("대분류별로 세부 카테고리를 묶고 ID와 이름을 반환한다")
    void getCategories_groupsChildrenByParent() {
        Category beauty = category(1L, "뷰티", null);
        Category dessert = category(2L, "카페/디저트", null);

        Category skincare = category(11L, "스킨케어", beauty);
        Category makeup = category(12L, "메이크업", beauty);
        Category cake = category(21L, "케이크", dessert);

        when(categoryRepository.findAllActiveWithParent())
                .thenReturn(List.of(
                        beauty,
                        dessert,
                        skincare,
                        makeup,
                        cake
                ));

        CategoryListResponse response =
                categoryQueryService.getCategories();

        assertThat(response.categories())
                .extracting(
                        CategoryResponse::categoryId,
                        CategoryResponse::name
                )
                .containsExactly(
                        tuple(1L, "뷰티"),
                        tuple(2L, "카페/디저트")
                );

        assertThat(response.categories().get(0).children())
                .extracting(
                        ChildCategoryResponse::categoryId,
                        ChildCategoryResponse::name
                )
                .containsExactly(
                        tuple(11L, "스킨케어"),
                        tuple(12L, "메이크업")
                );

        assertThat(response.categories().get(1).children())
                .extracting(
                        ChildCategoryResponse::categoryId,
                        ChildCategoryResponse::name
                )
                .containsExactly(
                        tuple(21L, "케이크")
                );

        // Repository 호출 횟수 검증이며 SQL 횟수 검증은 아니다.
        verify(categoryRepository).findAllActiveWithParent();
        verifyNoMoreInteractions(categoryRepository);
    }

    @Test
    @DisplayName("세부 카테고리가 없는 대분류는 빈 children을 반환한다")
    void getCategories_returnsEmptyChildren() {
        Category beauty = category(1L, "뷰티", null);

        when(categoryRepository.findAllActiveWithParent())
                .thenReturn(List.of(beauty));

        CategoryListResponse response =
                categoryQueryService.getCategories();

        assertThat(response.categories()).hasSize(1);

        CategoryResponse root = response.categories().get(0);

        assertThat(root.categoryId()).isEqualTo(1L);
        assertThat(root.name()).isEqualTo("뷰티");
        assertThat(root.children()).isEmpty();
    }

    @Test
    @DisplayName("조회 결과가 없으면 빈 categories를 반환한다")
    void getCategories_returnsEmptyCategories() {
        when(categoryRepository.findAllActiveWithParent())
                .thenReturn(List.of());

        CategoryListResponse response =
                categoryQueryService.getCategories();

        assertThat(response.categories()).isEmpty();
    }

    @Test
    @DisplayName("삭제된 대분류의 세부 카테고리는 응답에 포함하지 않는다")
    void getCategories_excludesChildrenOfDeletedParent() {
        Category deletedRoot = category(1L, "뷰티", null);
        Category skincare = category(11L, "스킨케어", deletedRoot);

        // 생성 시에는 정상 부모였으나 이후 삭제된 상황
        ReflectionTestUtils.setField(
                deletedRoot,
                "deletedAt",
                LocalDateTime.of(2026, 9, 19, 12, 0)
        );

        Category activeRoot = category(2L, "카페/디저트", null);
        Category cake = category(21L, "케이크", activeRoot);

        // Repository는 삭제된 대분류 자체를 제외한다.
        // 삭제되지 않은 자식은 조회될 수 있으므로 함께 전달한다.
        when(categoryRepository.findAllActiveWithParent())
                .thenReturn(List.of(activeRoot, skincare, cake));

        CategoryListResponse response =
                categoryQueryService.getCategories();

        assertThat(response.categories())
                .extracting(CategoryResponse::categoryId)
                .containsExactly(2L);

        assertThat(response.categories().get(0).children())
                .extracting(ChildCategoryResponse::categoryId)
                .containsExactly(21L);
    }

    private Category category(
            Long id,
            String name,
            Category parent
    ) {
        Category category = new Category(name, parent);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }
}
