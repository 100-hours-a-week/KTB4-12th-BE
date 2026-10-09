package com.gift.gift.domain.preference.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.*;

class UserDislikeCategoryTest {

    private User user;
    private Category root;
    private Category category;

    @BeforeEach
    void setUp() {
        user = new User(
                "preference@example.com",
                "$2a$10$" + "a".repeat(53),
                "테스트",
                LocalDate.of(1990, 1, 1)
        );
        root = new Category("뷰티", null);
        category = new Category("향수", root);
    }

    @Test
    @DisplayName("활성 대분류 카테고리를 비선호로 등록한다")
    void constructor_acceptsActiveRootCategory() {
        UserDislikeCategory dislike = new UserDislikeCategory(user, root);

        assertThat(dislike.getUser()).isSameAs(user);
        assertThat(dislike.getCategory()).isSameAs(root);
        assertThat(dislike.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("세부 카테고리는 비선호로 등록할 수 없다")
    void constructor_rejectsChildCategory() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UserDislikeCategory(user, category));
    }

    @Test
    @DisplayName("삭제된 대분류는 비선호로 등록할 수 없다")
    void constructor_rejectsDeletedCategory() {
        ReflectionTestUtils.setField(root, "deletedAt", LocalDateTime.now());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UserDislikeCategory(user, root));
    }

    @Test
    @DisplayName("부모가 삭제되어도 세부 카테고리를 비선호로 등록할 수 없다")
    void constructor_rejectsDeletedParent() {
        ReflectionTestUtils.setField(root, "deletedAt", LocalDateTime.now());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UserDislikeCategory(user, category));
    }

    @Test
    @DisplayName("부모가 생긴 기존 대분류는 비선호로 등록할 수 없다")
    void constructor_rejectsCategoryWithParent() {
        Category anotherRoot = new Category("기타", null);
        ReflectionTestUtils.setField(root, "parent", anotherRoot);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UserDislikeCategory(user, root));
    }

    @Test
    @DisplayName("사용자와 카테고리는 필수다")
    void constructor_rejectsNullReferences() {
        assertThatNullPointerException()
                .isThrownBy(() -> new UserDislikeCategory(null, category));

        assertThatNullPointerException()
                .isThrownBy(() -> new UserDislikeCategory(user, null));
    }
}
