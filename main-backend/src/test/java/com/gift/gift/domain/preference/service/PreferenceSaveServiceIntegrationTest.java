package com.gift.gift.domain.preference.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.exception.PreferenceException;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@Tag("integration")
@SpringBootTest
class PreferenceSaveServiceIntegrationTest {

    @Autowired
    private PreferenceSaveService preferenceSaveService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @MockitoSpyBean
    private UserDislikeCategoryRepository dislikeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("전체 교체·전체 해제·재선택은 기존 행을 재사용한다")
    void save_replacesClearsAndRestoresExistingRows() {
        User user = saveUser();
        Category beauty = saveRoot("뷰티");
        Category food = saveRoot("식품");
        Category digital = saveRoot("디지털");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(beauty.getId(), food.getId())
        );

        Long beautyDislikeId = dislikeRepository
                .findAllByUserIdIncludingDeleted(user.getId())
                .stream()
                .filter(dislike -> dislike.getCategory().getId()
                        .equals(beauty.getId()))
                .findFirst()
                .orElseThrow()
                .getId();

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(food.getId(), digital.getId())
        );

        assertThat(activeCategoryIds(user.getId()))
                .containsExactlyInAnyOrder(
                        food.getId(),
                        digital.getId()
                );

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of()
        );

        assertThat(activeCategoryIds(user.getId())).isEmpty();

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(beauty.getId())
        );
        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(beauty.getId())
        );

        List<UserDislikeCategory> history =
                dislikeRepository.findAllByUserIdIncludingDeleted(
                        user.getId()
                );

        assertThat(activeCategoryIds(user.getId()))
                .containsExactly(beauty.getId());
        assertThat(history).hasSize(3);
        assertThat(history)
                .filteredOn(dislike -> dislike.getCategory().getId()
                        .equals(beauty.getId()))
                .singleElement()
                .satisfies(dislike -> {
                    assertThat(dislike.getId())
                            .isEqualTo(beautyDislikeId);
                    assertThat(dislike.getDeletedAt()).isNull();
                });
    }

    @Test
    @DisplayName("5개는 저장하고 6개 요청은 거부하며 기존 선택을 보존한다")
    void save_enforcesMaximumCountAndPreservesExistingSelection() {
        User user = saveUser();
        List<Category> categories = List.of(
                saveRoot("카테고리-1"),
                saveRoot("카테고리-2"),
                saveRoot("카테고리-3"),
                saveRoot("카테고리-4"),
                saveRoot("카테고리-5"),
                saveRoot("카테고리-6")
        );
        List<Long> fiveIds = categories.subList(0, 5)
                .stream()
                .map(Category::getId)
                .toList();
        List<Long> sixIds = categories.stream()
                .map(Category::getId)
                .toList();

        preferenceSaveService.saveDislikeCategories(user.getId(), fiveIds);

        assertThatThrownBy(() ->
                preferenceSaveService.saveDislikeCategories(
                        user.getId(),
                        sixIds
                )
        ).isInstanceOfSatisfying(
                PreferenceException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_DISLIKE_CATEGORIES)
        );

        assertThat(activeCategoryIds(user.getId()))
                .containsExactlyInAnyOrderElementsOf(fiveIds);
    }

    @Test
    @DisplayName("세부·삭제·존재하지 않는 카테고리는 거부하고 기존 선택을 보존한다")
    void save_rejectsUnavailableCategoriesAndPreservesSelection() {
        User user = saveUser();
        Category selected = saveRoot("기존선택");
        Category parent = saveRoot("부모");
        Category child = categoryRepository.saveAndFlush(
                new Category(uniqueName("세부"), parent)
        );
        Category deleted = saveRoot("삭제대분류");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(selected.getId())
        );

        jdbcTemplate.update(
                "UPDATE categories "
                        + "SET deleted_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE id = ?",
                deleted.getId()
        );

        List<Long> unavailableIds = List.of(
                child.getId(),
                deleted.getId(),
                Long.MAX_VALUE
        );

        for (Long unavailableId : unavailableIds) {
            assertThatThrownBy(() ->
                    preferenceSaveService.saveDislikeCategories(
                            user.getId(),
                            List.of(unavailableId)
                    )
            ).isInstanceOfSatisfying(
                    PreferenceException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(
                                    ErrorCode.DISLIKE_CATEGORY_NOT_AVAILABLE
                            )
            );

            assertThat(activeCategoryIds(user.getId()))
                    .containsExactly(selected.getId());
        }
    }

    @Test
    @DisplayName("신규 선택 저장이 실패하면 기존 선택 해제도 롤백한다")
    void save_rollsBackAllChangesWhenPersistenceFails() {
        User user = saveUser();
        Category existing = saveRoot("롤백-기존");
        Category requested = saveRoot("롤백-신규");

        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(existing.getId())
        );

        doThrow(new DataAccessResourceFailureException(
                "simulated persistence failure"
        )).when(dislikeRepository).save(any(UserDislikeCategory.class));

        try {
            assertThatThrownBy(() ->
                    preferenceSaveService.saveDislikeCategories(
                            user.getId(),
                            List.of(requested.getId())
                    )
            ).isInstanceOf(DataAccessResourceFailureException.class);
        } finally {
            reset(dislikeRepository);
        }

        assertThat(activeCategoryIds(user.getId()))
                .containsExactly(existing.getId());
        assertThat(dislikeRepository
                .findAllByUserIdIncludingDeleted(user.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("동일 사용자의 동시 전체 교체 결과는 두 요청 중 한 집합으로 끝난다")
    void save_serializesConcurrentReplacementsForSameUser()
            throws Exception {
        User user = saveUser();
        List<Long> firstIds = saveRoots("동시-A", 5);
        List<Long> secondIds = saveRoots("동시-B", 5);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                preferenceSaveService.saveDislikeCategories(
                        user.getId(),
                        firstIds
                );
            });
            Future<?> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                preferenceSaveService.saveDislikeCategories(
                        user.getId(),
                        secondIds
                );
            });

            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        Set<Long> actual = new HashSet<>(
                activeCategoryIds(user.getId())
        );

        assertThat(actual).isIn(
                new HashSet<>(firstIds),
                new HashSet<>(secondIds)
        );
    }

    private User saveUser() {
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "테스트",
                LocalDate.of(1990, 1, 1)
        ));
    }

    private Category saveRoot(String prefix) {
        return categoryRepository.saveAndFlush(
                new Category(uniqueName(prefix), null)
        );
    }

    private List<Long> saveRoots(
            String prefix,
            int count
    ) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> saveRoot(prefix + "-" + index))
                .map(Category::getId)
                .toList();
    }

    private List<Long> activeCategoryIds(Long userId) {
        return dislikeRepository
                .findAllActiveCategoryIdsByUserId(userId);
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
