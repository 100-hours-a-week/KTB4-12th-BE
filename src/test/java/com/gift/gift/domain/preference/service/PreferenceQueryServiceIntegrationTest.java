package com.gift.gift.domain.preference.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest
@Transactional
class PreferenceQueryServiceIntegrationTest {

    @Autowired
    private PreferenceService preferenceService;

    @Autowired
    private UserDislikeCategoryRepository dislikeRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private Category root;
    private Category category;

    @BeforeEach
    void setUp() {
        user = saveUser();

        String suffix = UUID.randomUUID().toString();
        root = new Category("뷰티-" + suffix, null);
        entityManager.persist(root);

        category = new Category("향수-" + suffix, root);
        entityManager.persist(category);
    }

    @Test
    @DisplayName("상품의 대분류가 비선호이면 대분류 ID와 이름을 반환한다")
    void findMatchingWarning_returnsMatchingCategory() {
        saveDislike();

        Long userId = user.getId();
        Long categoryId = category.getId();
        String categoryName = root.getName();
        flushAndClear();

        assertThat(preferenceService.findMatchingWarning(userId, categoryId))
                .contains(new PreferenceWarningResult(root.getId(), categoryName));
    }

    @Test
    @DisplayName("등록된 비선호가 없으면 경고가 없다")
    void findMatchingWarning_returnsEmptyWhenNotRegistered() {
        flushAndClear();

        assertNoWarning();
    }

    @Test
    @DisplayName("다른 사용자의 비선호는 경고로 반환하지 않는다")
    void findMatchingWarning_excludesOtherUser() {
        saveDislike();
        User otherUser = saveUser();
        flushAndClear();

        assertThat(preferenceService.findMatchingWarning(
                otherUser.getId(),
                category.getId()
        )).isEmpty();
    }

    @Test
    @DisplayName("같은 대분류의 다른 세부 카테고리도 같은 대분류 경고를 반환한다")
    void findMatchingWarning_matchesSiblingCategory() {
        saveDislike();

        Category otherCategory = new Category(
                "스킨케어-" + UUID.randomUUID(),
                root
        );
        entityManager.persist(otherCategory);
        flushAndClear();

        assertThat(preferenceService.findMatchingWarning(
                user.getId(),
                otherCategory.getId()
        )).contains(new PreferenceWarningResult(root.getId(), root.getName()));
    }

    @Test
    @DisplayName("다른 대분류에 속한 상품은 경고가 없다")
    void findMatchingWarning_excludesOtherRoot() {
        saveDislike();
        Category otherRoot = new Category("식품-" + UUID.randomUUID(), null);
        entityManager.persist(otherRoot);
        Category otherCategory = new Category("간식-" + UUID.randomUUID(), otherRoot);
        entityManager.persist(otherCategory);
        flushAndClear();

        assertThat(preferenceService.findMatchingWarning(
                user.getId(), otherCategory.getId()
        )).isEmpty();
    }

    @Test
    @DisplayName("대분류 ID로 조회하면 경고가 없다")
    void findMatchingWarning_returnsEmptyForRootCategory() {
        saveDislike();
        flushAndClear();

        assertThat(preferenceService.findMatchingWarning(
                user.getId(),
                root.getId()
        )).isEmpty();
    }

    @Test
    @DisplayName("해제된 비선호는 경고에서 제외한다")
    void findMatchingWarning_excludesDeletedDislike() {
        UserDislikeCategory dislike = saveDislike();
        softDeleteDislike(dislike.getId());
        entityManager.clear();

        assertNoWarning();
    }

    @Test
    @DisplayName("삭제된 세부 카테고리는 경고에서 제외한다")
    void findMatchingWarning_excludesDeletedCategory() {
        saveDislike();
        softDeleteCategory(category.getId());
        entityManager.clear();

        assertNoWarning();
    }

    @Test
    @DisplayName("부모가 삭제된 카테고리는 경고에서 제외한다")
    void findMatchingWarning_excludesDeletedParent() {
        saveDislike();
        softDeleteCategory(root.getId());
        entityManager.clear();

        assertNoWarning();
    }

    @Test
    @DisplayName("부모가 대분류가 아닌 카테고리는 경고에서 제외한다")
    void findMatchingWarning_excludesInvalidHierarchy() {
        saveDislike();

        Category anotherRoot = new Category(
                "기타-" + UUID.randomUUID(),
                null
        );
        entityManager.persist(anotherRoot);
        entityManager.flush();

        entityManager.createQuery("""
                update Category c
                set c.parent = :parent
                where c.id = :id
                """)
                .setParameter("parent", anotherRoot)
                .setParameter("id", root.getId())
                .executeUpdate();
        entityManager.clear();

        assertNoWarning();
    }

    @Test
    @DisplayName("동일 사용자와 카테고리 조합은 중복 저장할 수 없다")
    void save_rejectsDuplicateUserCategory() {
        saveDislike();

        assertThatThrownBy(() -> dislikeRepository.saveAndFlush(
                new UserDislikeCategory(user, root)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("해제된 행도 동일 사용자와 카테고리의 중복 저장을 막는다")
    void save_rejectsDuplicateEvenAfterSoftDelete() {
        UserDislikeCategory dislike = saveDislike();
        softDeleteDislike(dislike.getId());

        assertThatThrownBy(() -> dislikeRepository.saveAndFlush(
                new UserDislikeCategory(user, root)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private User saveUser() {
        User savedUser = new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "테스트",
                LocalDate.of(1990, 1, 1)
        );
        entityManager.persist(savedUser);
        return savedUser;
    }

    private UserDislikeCategory saveDislike() {
        return dislikeRepository.saveAndFlush(
                new UserDislikeCategory(user, root)
        );
    }

    private void softDeleteDislike(Long dislikeId) {
        entityManager.flush();
        entityManager.createQuery("""
                update UserDislikeCategory d
                set d.deletedAt = :deletedAt
                where d.id = :id
                """)
                .setParameter("deletedAt", LocalDateTime.now())
                .setParameter("id", dislikeId)
                .executeUpdate();
    }

    private void softDeleteCategory(Long categoryId) {
        entityManager.flush();
        entityManager.createQuery("""
                update Category c
                set c.deletedAt = :deletedAt
                where c.id = :id
                """)
                .setParameter("deletedAt", LocalDateTime.now())
                .setParameter("id", categoryId)
                .executeUpdate();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertNoWarning() {
        assertThat(preferenceService.findMatchingWarning(
                user.getId(),
                category.getId()
        )).isEmpty();
    }
}
